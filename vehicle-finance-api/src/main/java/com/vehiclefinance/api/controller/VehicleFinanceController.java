package com.vehiclefinance.api.controller;

import com.vehiclefinance.api.service.VehicleFinanceService;
import com.vehiclefinance.api.service.StateSnapshotStore;
import com.vehiclefinance.api.service.OtpVerificationService;
import com.vehiclefinance.api.service.QuarantinedDocumentStorage;
import com.vehiclefinance.api.service.QuarantinedDocumentStorage.StoredDocument;
import com.vehiclefinance.api.service.EsignService;
import com.vehiclefinance.api.config.DocumentStorageProperties;
import com.vehiclefinance.api.config.FundingProperties;
import com.vehiclefinance.api.service.FundingGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

@RestController
@RequestMapping("/")
public class VehicleFinanceController {

    private final VehicleFinanceService service;
    private final StateSnapshotStore stateSnapshotStore;
    private final OtpVerificationService otpVerificationService;
    private final QuarantinedDocumentStorage documentStorage;
    private final DocumentStorageProperties documentStorageProperties;
    private final EsignService esignService;
    private final FundingProperties fundingProperties;
    private final FundingGateway fundingGateway;
    private final Map<String, IdempotentCreate> createRequests = new HashMap<>();

    private record IdempotentCreate(String fingerprint, Map<String, Object> response) { }

    public VehicleFinanceController(VehicleFinanceService service, StateSnapshotStore stateSnapshotStore,
                                    OtpVerificationService otpVerificationService, QuarantinedDocumentStorage documentStorage,
                                    DocumentStorageProperties documentStorageProperties, EsignService esignService,
                                    FundingProperties fundingProperties, FundingGateway fundingGateway) {
        this.service = service;
        this.stateSnapshotStore = stateSnapshotStore;
        this.otpVerificationService = otpVerificationService;
        this.documentStorage = documentStorage;
        this.documentStorageProperties = documentStorageProperties;
        this.esignService = esignService;
        this.fundingProperties = fundingProperties;
        this.fundingGateway = fundingGateway;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/bootstrap")
    public Map<String, Object> bootstrap() {
        return service.bootstrap();
    }

    @PostMapping("/api/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Map<String, Object> createCustomer(@RequestBody Map<String, Object> payload) {
        String email = String.valueOf(payload.getOrDefault("email", ""));
        String mobile = String.valueOf(payload.getOrDefault("mobile", ""));
        if (!otpVerificationService.hasVerifiedContacts(email, mobile)) throw new IllegalStateException("Verify both email and mobile using OTP before creating the customer profile.");
        Map<String, Object> result = service.createCustomer(payload);
        if (!otpVerificationService.consumeVerifiedContacts(email, mobile)) throw new IllegalStateException("Contact verification expired; verify both channels again.");
        persist();
        return result;
    }

    public record OtpChallengeRequest(@NotBlank String channel, @NotBlank String destination) { }
    public record OtpVerifyRequest(@NotBlank String challengeId, @NotBlank String code) { }

    @PostMapping("/api/otp/challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpVerificationService.Challenge createOtpChallenge(@Valid @RequestBody OtpChallengeRequest request) {
        OtpVerificationService.Channel channel;
        try { channel = OtpVerificationService.Channel.valueOf(request.channel().trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("OTP channel must be EMAIL or SMS."); }
        return otpVerificationService.issue(channel, request.destination());
    }

    @PostMapping("/api/otp/challenges/verify")
    public Map<String, Object> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        if (!otpVerificationService.verify(request.challengeId(), request.code())) {
            throw new IllegalArgumentException("Verification code is invalid, expired, or has exceeded the attempt limit.");
        }
        return Map.of("verified", true);
    }

    @GetMapping("/api/customers/{customerId}")
    public Map<String, Object> getCustomer(@PathVariable String customerId) { return service.getCustomer(customerId); }

    @org.springframework.web.bind.annotation.PatchMapping("/api/customers/{customerId}/name")
    public synchronized Map<String, Object> updateCustomerName(@PathVariable String customerId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> result = service.updateCustomerName(customerId, payload);
        persist();
        return result;
    }

    @org.springframework.web.bind.annotation.PatchMapping("/api/customers/{customerId}/details")
    public synchronized Map<String, Object> updateCustomerDetails(@PathVariable String customerId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> details = service.validateCustomerDetails(payload);
        Map<String, Object> customer = service.getCustomer(customerId);
        String email = (String) details.get("email");
        String mobile = (String) details.get("mobile");
        String existingEmail = String.valueOf(customer.get("email")).trim().toLowerCase(java.util.Locale.ROOT);
        String existingMobile = String.valueOf(customer.get("mobile")).replaceAll("[\\s()-]", "");
        if ((!email.equals(existingEmail) || !mobile.equals(existingMobile))
                && !otpVerificationService.consumeVerifiedContacts(email, mobile)) {
            throw new IllegalStateException("Verify both email and mobile using OTP before changing customer contacts.");
        }
        Map<String, Object> result = service.updateCustomerDetails(customerId, details);
        persist();
        return result;
    }

    @GetMapping("/api/dealers")
    public Map<String, Object> listDealers(@RequestParam(defaultValue = "ACTIVE") String status,
                                           @RequestParam(required = false) String region,
                                           @RequestParam(required = false) String q) {
        return service.dealersResponse(status, region, q);
    }

    @GetMapping("/api/dealers/{dealerId}")
    public Map<String, Object> getDealer(@PathVariable String dealerId) { return service.getDealer(dealerId); }

    @PostMapping("/api/dealers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDealer(@RequestBody Map<String, Object> payload) { Map<String, Object> result = service.createDealer(payload); persist(); return result; }

    @GetMapping("/api/dealers/{dealerId}/branches")
    public Map<String, Object> listDealerBranches(@PathVariable String dealerId) { return service.dealerBranchesResponse(dealerId); }

    @PostMapping("/api/dealers/{dealerId}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDealerBranch(@PathVariable String dealerId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> result = service.createDealerBranch(dealerId, payload); persist(); return result;
    }

    @GetMapping("/api/dealers/{dealerId}/staff")
    public Map<String, Object> listDealerStaff(@PathVariable String dealerId) { return service.dealerStaffResponse(dealerId); }

    @PostMapping("/api/dealers/{dealerId}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDealerStaff(@PathVariable String dealerId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> result = service.createDealerStaff(dealerId, payload); persist(); return result;
    }

    @GetMapping("/api/vehicles/{vehicleId}")
    public Map<String, Object> getVehicle(@PathVariable String vehicleId) { return service.getVehicle(vehicleId); }

    @PostMapping("/api/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createVehicle(@RequestBody Map<String, Object> payload) { Map<String, Object> result = service.createVehicle(payload); persist(); return result; }

    @org.springframework.web.bind.annotation.PatchMapping("/api/vehicles/{vehicleId}")
    public Map<String, Object> updateVehicle(@PathVariable String vehicleId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> result = service.updateVehicle(vehicleId, payload); persist(); return result;
    }

    @GetMapping("/api/audit")
    public Map<String, Object> audit() { return service.auditResponse(); }

    @PostMapping("/api/kyc/cases")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> createKycCase(@RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> result = service.createKycCase(payload == null ? Map.of() : payload); persist(); return result;
    }

    @GetMapping("/api/kyc/cases")
    public Map<String, Object> listKycCases(@RequestParam(required = false) String customerId,
                                            @RequestParam(required = false) String status) {
        return service.kycCasesResponse(customerId, status);
    }

    @GetMapping("/api/kyc/cases/{caseId}")
    public Map<String, Object> getKycCase(@PathVariable String caseId) { return service.getKycCase(caseId); }

    @PostMapping("/api/kyc/cases/{caseId}/evidence")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<Void> addKycEvidence(@PathVariable String caseId, @RequestBody Map<String, Object> evidence) {
        service.addKycEvidence(caseId, evidence);
        persist();
        return ResponseEntity.accepted().build();
    }

    @PostMapping(value = "/api/documents/upload", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StoredDocument uploadDocument(@RequestPart("file") MultipartFile file,
                                         @RequestParam String ownerType,
                                         @RequestParam String ownerId,
                                         @RequestParam String documentType) {
        if (!documentStorageProperties.isUploadEnabled()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Secure upload is disabled until authenticated ownership and scanning integrations are configured.");
        }
        String normalizedOwner = ownerType.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("IDENTITY PROOF", "ADDRESS PROOF", "INCOME PROOF", "SUPPORTING DOCUMENT", "AGREEMENT").contains(documentType.trim().toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported document category.");
        }
        switch (normalizedOwner) {
            case "KYC_CASE" -> service.getKycCase(ownerId);
            case "LOAN_APPLICATION" -> service.getLoan(ownerId);
            case "CUSTOMER" -> service.getCustomer(ownerId);
            default -> throw new IllegalArgumentException("Document owner type must be CUSTOMER, KYC_CASE, or LOAN_APPLICATION.");
        }
        StoredDocument stored = documentStorage.store(file);
        Map<String, Object> metadata = Map.of("type", documentType, "objectRef", stored.objectRef(),
                "name", stored.originalName(), "scanStatus", stored.scanStatus(), "sizeBytes", stored.sizeBytes(),
                "contentType", stored.contentType());
        switch (normalizedOwner) {
            case "KYC_CASE" -> service.addKycEvidence(ownerId, metadata);
            case "LOAN_APPLICATION" -> service.addDocument(ownerId, metadata);
            case "CUSTOMER" -> service.addCustomerDocument(ownerId, metadata);
            default -> throw new IllegalArgumentException("Document owner type must be CUSTOMER, KYC_CASE, or LOAN_APPLICATION.");
        }
        persist();
        return stored;
    }

    @PostMapping("/api/kyc/cases/{caseId}/decision")
    public ResponseEntity<Void> decideKycCase(@PathVariable String caseId, @RequestBody Map<String, Object> payload) {
        service.decideKycCase(caseId, payload);
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/loans/{loanId}/documents")
    public ResponseEntity<Void> registerLoanDocument(@PathVariable String loanId,
                                                    @RequestBody Map<String, Object> metadata,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        service.addDocument(loanId, metadata);
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized Map<String, Object> createLoan(@RequestBody Map<String, Object> payload,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String fingerprint = payload.toString();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotentCreate previous = createRequests.get(idempotencyKey);
            if (previous != null) {
                if (!Objects.equals(previous.fingerprint(), fingerprint)) throw new IllegalArgumentException("Idempotency key was already used with a different request.");
                return previous.response();
            }
        }
        Map<String, Object> response = service.createLoan(payload);
        persist();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) createRequests.put(idempotencyKey, new IdempotentCreate(fingerprint, response));
        return response;
    }

    @GetMapping("/api/loans")
    public Map<String, Object> listLoans(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String customerId,
                                         @RequestParam(required = false) String dealerId,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        return service.loansResponse(status, customerId, dealerId, q, page, size);
    }

    @PutMapping("/api/loans/{loanId}")
    public Map<String, Object> updateDraft(@PathVariable String loanId, @RequestBody Map<String, Object> payload) {
        Map<String, Object> result = service.updateDraft(loanId, payload); persist(); return result;
    }

    @PostMapping("/api/loans/{loanId}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<Void> submitLoan(@PathVariable String loanId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        service.submitLoan(loanId);
        persist();
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/loans/{loanId}")
    public Map<String, Object> getLoan(@PathVariable String loanId) {
        return service.getLoan(loanId);
    }

    @GetMapping("/api/loans/{loanId}/timeline")
    public java.util.List<Map<String, Object>> getLoanTimeline(@PathVariable String loanId) {
        return service.getTimeline(loanId);
    }

    @GetMapping("/api/offers/{offerId}")
    public Map<String, Object> getOffer(@PathVariable String offerId) { return service.getOffer(offerId); }

    @PostMapping("/api/loans/{loanId}/decision")
    public ResponseEntity<Void> decideLoan(@PathVariable String loanId,
                                          @RequestBody Map<String, Object> payload,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        service.decideLoan(loanId, payload);
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/loans/{loanId}/kyc-decision")
    public ResponseEntity<Void> recordKycDecision(@PathVariable String loanId, @RequestBody Map<String, Object> payload) {
        service.recordKycDecision(loanId, payload);
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/loans/{loanId}/recommendation")
    public ResponseEntity<Void> recordRecommendation(@PathVariable String loanId, @RequestBody Map<String, Object> payload) {
        service.recordRecommendation(loanId, payload);
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/offers/{offerId}/accept")
    public ResponseEntity<Void> acceptOffer(@PathVariable String offerId,
                                          @RequestBody Map<String, Object> payload,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Integer offerVersion = payload.get("offerVersion") == null ? 1 : Integer.valueOf(String.valueOf(payload.get("offerVersion")));
        service.acceptOffer(offerId, offerVersion,
            String.valueOf(payload.getOrDefault("consentVersion", "")),
            Boolean.TRUE.equals(payload.get("consentAccepted")),
            String.valueOf(payload.getOrDefault("acceptedBy", "Demo customer")));
        persist();
        return ResponseEntity.noContent().build();
    }

    public record EsignStartRequest(Integer offerVersion, String disclosureVersion, String customerId) { }
    public record EsignCompleteRequest(Boolean consentAccepted, String disclosureVersion) { }

    @PostMapping("/api/offers/{offerId}/esign-sessions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EsignService.Session startEsign(@PathVariable String offerId, @RequestBody EsignStartRequest request) {
        Map<String, Object> offer = service.getOffer(offerId);
        if (!Objects.equals(((Number) offer.get("version")).intValue(), request.offerVersion())) throw new IllegalStateException("Offer version changed before signing started.");
        if (!Objects.equals(offer.get("disclosureVersion"), request.disclosureVersion())) throw new IllegalStateException("Offer disclosure version does not match the current offer.");
        if (request.customerId() == null || request.customerId().isBlank()) throw new IllegalArgumentException("Customer reference is required.");
        Map<String, Object> loan = service.findLoanByOffer(offerId);
        if (!Objects.equals(loan.get("customerId"), request.customerId())) throw new IllegalStateException("Customer does not own this offer.");
        if (!"CONDITIONS_PENDING".equals(loan.get("status")) || offer.get("acceptedAt") == null) throw new IllegalStateException("Accept the offer before signing its agreement.");
        return esignService.createSession(offerId, request.customerId());
    }

    @GetMapping("/api/esign-sessions/{sessionId}")
    public EsignService.SessionStateView getEsignSession(@PathVariable String sessionId) { return esignService.status(sessionId); }

    @PostMapping("/api/esign-sessions/{sessionId}/complete")
    public EsignService.SessionStateView completeSandboxEsign(@PathVariable String sessionId, @RequestBody EsignCompleteRequest request) {
        return esignService.completeSandboxSession(sessionId, Boolean.TRUE.equals(request.consentAccepted()), request.disclosureVersion());
    }

    public record AgreementSignedRequest(String sessionId, Integer offerVersion, String disclosureVersion, String customerId) { }

    @PostMapping("/api/loans/{loanId}/agreement-signed")
    public Map<String, Object> agreementSigned(@PathVariable String loanId, @RequestBody AgreementSignedRequest request) {
        EsignService.SessionStateView session = esignService.status(request.sessionId());
        if (!"SIGNED".equals(session.status())) throw new IllegalStateException("The signing provider has not marked this agreement signed.");
        if (!Objects.equals(session.customerId(), request.customerId())) throw new IllegalStateException("Signed agreement customer does not match the application owner.");
        Map<String, Object> loan = service.getLoan(loanId);
        Object rawOffer = loan.get("offer");
        if (!(rawOffer instanceof Map<?, ?> offerData) || !Objects.equals(offerData.get("id"), session.agreementId())) {
            throw new IllegalStateException("Signing session offer does not belong to this application.");
        }
        Map<String, Object> updated = service.acceptSignedAgreement(session.agreementId(), request.offerVersion(),
                request.disclosureVersion(), request.customerId(), session.sessionId());
        persist();
        return updated;
    }

    @PostMapping("/api/webhooks/esign")
    public ResponseEntity<Void> esignWebhook(@RequestHeader("X-Provider-Signature") String signature, @RequestBody String body) {
        if (!esignService.verifyProviderWebhook(body, signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        // Vendor-specific webhook event mapping intentionally remains disabled until a provider is selected.
        return ResponseEntity.accepted().build();
    }

    @org.springframework.web.bind.annotation.PatchMapping("/api/loans/{loanId}/conditions/{conditionId}")
    public ResponseEntity<Void> updateCondition(@PathVariable String loanId, @PathVariable String conditionId,
                                                @RequestBody Map<String, Object> payload) {
        service.updateCondition(loanId, conditionId, Boolean.TRUE.equals(payload.get("done")));
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/loans/{loanId}/funding")
    public ResponseEntity<FundingGateway.FundingReceipt> requestFunding(@PathVariable String loanId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!"sandbox".equalsIgnoreCase(fundingProperties.getMode())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Funding provider is disabled until an approved provider and production authorization are configured.");
        }
        FundingGateway.FundingReceipt receipt = service.requestFunding(loanId, idempotencyKey, fundingGateway);
        persist();
        return ResponseEntity.accepted().body(receipt);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/api/dealers/{dealerId}")
    public ResponseEntity<Void> updateDealer(@PathVariable String dealerId, @RequestBody Map<String, Object> payload) {
        service.updateDealerStatus(dealerId, String.valueOf(payload.getOrDefault("status", "")));
        persist();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/vehicles/search")
    public Map<String, Object> searchVehicles(@RequestBody Map<String, Object> filters) {
        return service.searchVehicles(filters);
    }

    private void persist() {
        stateSnapshotStore.save(service.exportSnapshot());
    }
}
