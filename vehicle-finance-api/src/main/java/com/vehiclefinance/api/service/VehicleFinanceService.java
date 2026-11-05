package com.vehiclefinance.api.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class VehicleFinanceService {

    private final List<Map<String, Object>> customers = new ArrayList<>();
    private final List<Map<String, Object>> dealers = new ArrayList<>();
    private final List<Map<String, Object>> dealerBranches = new ArrayList<>();
    private final List<Map<String, Object>> dealerStaff = new ArrayList<>();
    private final List<Map<String, Object>> vehicles = new ArrayList<>();
    private final List<Map<String, Object>> loans = new ArrayList<>();
    private final List<Map<String, Object>> audit = new ArrayList<>();
    private final List<Map<String, Object>> kycCases = new ArrayList<>();
    private final List<Map<String, Object>> customerDocuments = new ArrayList<>();
    private final List<Map<String, Object>> fundingRequests = new ArrayList<>();
    @Autowired
    public VehicleFinanceService(StateSnapshotStore snapshotStore) {
        seed();
        snapshotStore.load().ifPresent(this::restoreSnapshot);
    }

    /** Test-only constructor retains isolated seeded service behavior without a database. */
    public VehicleFinanceService() {
        seed();
    }

    public synchronized Map<String, Object> exportSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("customers", customers);
        snapshot.put("dealers", dealers);
        snapshot.put("dealerBranches", dealerBranches);
        snapshot.put("dealerStaff", dealerStaff);
        snapshot.put("vehicles", vehicles);
        snapshot.put("loans", loans);
        snapshot.put("audit", audit);
        snapshot.put("kycCases", kycCases);
        snapshot.put("customerDocuments", customerDocuments);
        snapshot.put("fundingRequests", fundingRequests);
        return snapshot;
    }

    private void restoreSnapshot(Map<String, Object> snapshot) {
        replaceList(customers, snapshot.get("customers"));
        replaceList(dealers, snapshot.get("dealers"));
        replaceList(dealerBranches, snapshot.get("dealerBranches"));
        replaceList(dealerStaff, snapshot.get("dealerStaff"));
        replaceList(vehicles, snapshot.get("vehicles"));
        replaceList(loans, snapshot.get("loans"));
        replaceList(audit, snapshot.get("audit"));
        replaceList(kycCases, snapshot.get("kycCases"));
        replaceList(customerDocuments, snapshot.get("customerDocuments"));
        replaceList(fundingRequests, snapshot.get("fundingRequests"));
        if (customers.isEmpty() || dealers.isEmpty() || vehicles.isEmpty()) {
            throw new IllegalStateException("Persisted vehicle-finance snapshot is incomplete; refusing to replace it with seed data.");
        }
        for (Map<String, Object> loan : loans) {
            Object storedOffer = loan.get("offer");
            if (storedOffer instanceof Map<?, ?>) {
                Map<String, Object> offer = asStringMap(storedOffer);
                offer.putIfAbsent("disclosureVersion", "VFS-OFFER-2026.10");
            }
        }
    }

    private void replaceList(List<Map<String, Object>> target, Object value) {
        if (!(value instanceof List<?> records)) return;
        target.clear();
        for (Object record : records) {
            if (record instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(String.valueOf(key), item));
                target.add(copy);
            }
        }
    }

    public Map<String, Object> bootstrap() {
        return Map.of(
                "customer", customers.get(0),
                "vehicles", List.copyOf(vehicles),
                "dealers", List.copyOf(dealers),
                "dealerBranches", List.copyOf(dealerBranches),
                "dealerStaff", List.copyOf(dealerStaff),
                "loans", List.copyOf(loans),
                "audit", List.copyOf(audit),
                "kycCases", List.copyOf(kycCases)
        );
    }

    public Map<String, Object> getCustomer(String customerId) {
        return customers.stream().filter(item -> Objects.equals(item.get("id"), customerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Customer not found."));
    }

    public synchronized Map<String, Object> updateCustomerName(String customerId, Map<String, Object> payload) {
        Map<String, Object> customer = getCustomer(customerId);
        Map<String, Object> names = validateCustomerName(payload);
        String firstName = (String) names.get("firstName");
        String lastName = (String) names.get("lastName");
        customer.put("firstName", firstName);
        customer.put("lastName", lastName);
        for (Map<String, Object> loan : loans) {
            if (Objects.equals(loan.get("customerId"), customerId) && Objects.equals(loan.get("status"), "DRAFT")) {
                loan.put("applicantName", firstName + " " + lastName);
            }
        }
        addAudit("Customer name updated", customerId, "Customer");
        return new LinkedHashMap<>(customer);
    }

    private Map<String, Object> validateCustomerName(Map<String, Object> payload) {
        if (payload == null || payload.keySet().stream().anyMatch(key -> !List.of("firstName", "lastName").contains(key))) {
            throw new IllegalArgumentException("Only firstName and lastName can be updated here.");
        }
        if (!(payload.get("firstName") instanceof String) || !(payload.get("lastName") instanceof String)) {
            throw new IllegalArgumentException("First name and last name must be text.");
        }
        String firstName = required(payload, "firstName");
        String lastName = required(payload, "lastName");
        if (firstName.length() > 100 || lastName.length() > 100) {
            throw new IllegalArgumentException("First name and last name must each be 100 characters or fewer.");
        }
        return Map.of("firstName", firstName, "lastName", lastName);
    }

    public Map<String, Object> validateCustomerDetails(Map<String, Object> payload) {
        List<String> fields = List.of("firstName", "lastName", "email", "mobile");
        if (payload == null || payload.size() != fields.size() || !payload.keySet().containsAll(fields)) {
            throw new IllegalArgumentException("Only firstName, lastName, email and mobile are accepted, and all are required.");
        }
        if (fields.stream().anyMatch(field -> !(payload.get(field) instanceof String))) {
            throw new IllegalArgumentException("Customer details must be text.");
        }
        Map<String, Object> details = new LinkedHashMap<>(validateCustomerName(Map.of(
                "firstName", payload.get("firstName"), "lastName", payload.get("lastName"))));
        String email = required(payload, "email").toLowerCase(java.util.Locale.ROOT);
        String mobile = required(payload, "mobile").replaceAll("[\\s()-]", "");
        if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Enter a valid email address of 254 characters or fewer.");
        }
        if (!mobile.matches("^\\+?[1-9]\\d{9,14}$")) {
            throw new IllegalArgumentException("Enter a valid mobile number with country code.");
        }
        details.put("email", email);
        details.put("mobile", mobile);
        return details;
    }

    public synchronized Map<String, Object> updateCustomerDetails(String customerId, Map<String, Object> payload) {
        Map<String, Object> customer = getCustomer(customerId);
        Map<String, Object> details = validateCustomerDetails(payload);
        updateCustomerName(customerId, Map.of("firstName", details.get("firstName"), "lastName", details.get("lastName")));
        customer.put("email", details.get("email"));
        customer.put("mobile", details.get("mobile"));
        return new LinkedHashMap<>(customer);
    }

    public synchronized void addCustomerDocument(String customerId, Map<String, Object> metadata) {
        getCustomer(customerId);
        String type = required(metadata, "type");
        String objectRef = required(metadata, "objectRef");
        if (customerDocuments.stream().anyMatch(item -> Objects.equals(item.get("objectRef"), objectRef))) return;
        customerDocuments.add(map("id", metadata.getOrDefault("id", "cust-doc-" + (customerDocuments.size() + 1)),
                "customerId", customerId, "type", type, "objectRef", objectRef,
                "name", metadata.getOrDefault("name", "document"), "addedAt", Instant.now().toString(),
                "verified", false, "scanStatus", "PENDING"));
        addAudit("Customer document quarantined", customerId + ":" + type, "Customer");
    }

    public Map<String, Object> createCustomer(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("consent"))) throw new IllegalArgumentException("Explicit consent is required.");
        String customerId = "C-1001";
        Map<String, Object> customer = customers.get(0);
        String firstName = required(payload, "firstName");
        String lastName = required(payload, "lastName");
        String email = required(payload, "email");
        String mobile = required(payload, "mobile");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        String normalizedMobile = mobile.replaceAll("[\\s()-]", "");
        if (!normalizedMobile.matches("^\\+?[1-9]\\d{9,14}$")) throw new IllegalArgumentException("Enter a valid mobile number with country code.");
        customer.put("firstName", firstName);
        customer.put("lastName", lastName);
        customer.put("email", email);
        customer.put("mobile", normalizedMobile);
        customer.put("consent", Boolean.TRUE.equals(payload.get("consent")));
        customer.put("consentVersion", payload.getOrDefault("consentVersion", "VFS-PRIVACY-1"));
        customer.put("kycStatus", "NOT_STARTED");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("customerId", customerId);
        return response;
    }

    public Map<String, Object> dealersResponse(String status) {
        return dealersResponse(status, null, null);
    }

    public Map<String, Object> dealersResponse(String status, String region, String query) {
        List<Map<String, Object>> filtered = dealers.stream()
                .filter(item -> "ALL".equalsIgnoreCase(status) || Objects.equals(item.get("status"), status))
                .filter(item -> region == null || region.isBlank() || String.valueOf(item.get("region")).equalsIgnoreCase(region))
                .filter(item -> query == null || query.isBlank() || (item.get("name") + " " + item.get("id")).toString().toLowerCase().contains(query.toLowerCase()))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", filtered);
        response.put("total", filtered.size());
        return response;
    }

    public Map<String, Object> getDealer(String dealerId) { return findDealer(dealerId); }

    public Map<String, Object> dealerBranchesResponse(String dealerId) {
        findDealer(dealerId);
        List<Map<String, Object>> items = dealerBranches.stream().filter(item -> Objects.equals(item.get("dealerId"), dealerId)).toList();
        return map("items", items, "total", items.size());
    }

    public synchronized Map<String, Object> createDealerBranch(String dealerId, Map<String, Object> payload) {
        Map<String, Object> dealer = findDealer(dealerId);
        String name = required(payload, "name");
        String branchId = dealerId + "-B" + String.format("%02d", dealerBranches.stream().filter(item -> Objects.equals(item.get("dealerId"), dealerId)).count() + 1);
        Map<String, Object> branch = map("id", branchId, "dealerId", dealerId, "name", name,
                "address", payload.getOrDefault("address", ""), "city", payload.getOrDefault("city", ""),
                "region", payload.getOrDefault("region", dealer.get("region")), "status", "PENDING");
        dealerBranches.add(branch);
        dealer.put("branches", ((Number) dealer.get("branches")).intValue() + 1);
        addAudit("Dealer branch submitted", branchId, "Dealer user");
        return branch;
    }

    public Map<String, Object> dealerStaffResponse(String dealerId) {
        findDealer(dealerId);
        List<Map<String, Object>> items = dealerStaff.stream().filter(item -> Objects.equals(item.get("dealerId"), dealerId)).toList();
        return map("items", items, "total", items.size());
    }

    public synchronized Map<String, Object> createDealerStaff(String dealerId, Map<String, Object> payload) {
        findDealer(dealerId);
        String name = required(payload, "name");
        String email = required(payload, "email");
        String id = dealerId + "-U" + String.format("%03d", dealerStaff.stream().filter(item -> Objects.equals(item.get("dealerId"), dealerId)).count() + 1);
        Map<String, Object> staff = map("id", id, "dealerId", dealerId, "name", name, "email", email,
                "role", payload.getOrDefault("role", "DEALER_USER"), "status", "PENDING_INVITATION");
        dealerStaff.add(staff);
        addAudit("Dealer staff invitation created", id, "Dealer administrator");
        return staff;
    }

    public synchronized Map<String, Object> createDealer(Map<String, Object> payload) {
        String name = required(payload, "name");
        if (dealers.stream().anyMatch(item -> name.equalsIgnoreCase(String.valueOf(item.get("name"))))) {
            throw new IllegalStateException("A dealer with this name already exists.");
        }
        String id = "D-" + String.format("%04d", 1047 + dealers.size() - 5);
        Map<String, Object> dealer = map("id", id, "name", name,
                "region", payload.getOrDefault("region", "Unspecified"),
                "status", "PENDING", "branches", 0,
                "contact", payload.getOrDefault("contact", ""),
                "address", payload.getOrDefault("address", ""));
        dealers.add(dealer);
        addAudit("Dealer submitted for review", id, "Dealer user");
        return dealer;
    }

    public Map<String, Object> createKycCase() {
        return createKycCase(Map.of());
    }

    public synchronized Map<String, Object> createKycCase(Map<String, Object> payload) {
        String caseId = "KYC-CASE-" + String.format("%04d", kycCases.size() + 1001);
        String customerId = String.valueOf(payload.getOrDefault("customerId", customers.get(0).get("id")));
        getCustomer(customerId);
        Map<String, Object> kycCase = map("kycCaseId", caseId, "customerId", customerId,
                "status", "PENDING", "createdAt", Instant.now().toString(),
                "evidence", new ArrayList<Map<String, Object>>(), "history", new ArrayList<Map<String, Object>>());
        kycCases.add(kycCase);
        addAudit("KYC case started", caseId, "Customer");
        return map("kycCaseId", caseId, "uploadSessionId", "UPLOAD-" + caseId.substring("KYC-CASE-".length()));
    }

    public Map<String, Object> getKycCase(String caseId) { return findKycCase(caseId); }

    public Map<String, Object> kycCasesResponse(String customerId, String status) {
        List<Map<String, Object>> items = kycCases.stream()
                .filter(item -> customerId == null || customerId.isBlank() || Objects.equals(item.get("customerId"), customerId))
                .filter(item -> status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) || Objects.equals(item.get("status"), status))
                .toList();
        return map("items", items, "total", items.size());
    }

    public synchronized void addKycEvidence(String caseId, Map<String, Object> evidence) {
        Map<String, Object> kycCase = findKycCase(caseId);
        if (!"PENDING".equals(kycCase.get("status")) && !"REWORK".equals(kycCase.get("status"))) {
            throw new IllegalStateException("Evidence cannot be added in the current KYC state.");
        }
        String type = required(evidence, "type");
        String objectRef = required(evidence, "objectRef");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidenceList = (List<Map<String, Object>>) kycCase.get("evidence");
        evidenceList.add(map("id", "KYC-DOC-" + (evidenceList.size() + 1), "type", type,
            "objectRef", objectRef, "name", evidence.getOrDefault("name", "document"),
            "contentType", evidence.getOrDefault("contentType", "application/octet-stream"),
            "sizeBytes", evidence.getOrDefault("sizeBytes", 0),
            "receivedAt", Instant.now().toString(), "scanStatus", "PENDING"));
        addAudit("KYC evidence reference registered", caseId + ":" + type, "Customer");
    }

    public synchronized void decideKycCase(String caseId, Map<String, Object> body) {
        Map<String, Object> kycCase = findKycCase(caseId);
        requireOneOfValues(kycCase.get("status"), "PENDING", "REWORK");
        String outcome = required(body, "outcome");
        String reason = String.valueOf(body.getOrDefault("reason", ""));
        if (!List.of("VERIFIED", "REWORK", "REJECTED").contains(outcome)) throw new IllegalArgumentException("Unsupported KYC outcome.");
        if (!"VERIFIED".equals(outcome) && reason.isBlank()) throw new IllegalArgumentException("A reason is required for rework or rejection.");
        if ("VERIFIED".equals(outcome)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) kycCase.get("evidence");
            if (evidence.isEmpty() || evidence.stream().anyMatch(item -> !"CLEAN".equals(item.get("scanStatus")))) {
                throw new IllegalStateException("KYC verification requires evidence that passed secure document scanning.");
            }
        }
        kycCase.put("status", outcome);
        kycCase.put("reviewedAt", Instant.now().toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) kycCase.get("history");
        history.add(map("outcome", outcome, "reason", reason, "at", Instant.now().toString(), "actor", "KYC analyst"));
        for (Map<String, Object> customer : customers) {
            if (Objects.equals(customer.get("id"), kycCase.get("customerId"))) customer.put("kycStatus", outcome);
        }
        addAudit("KYC decision recorded", caseId + ":" + outcome, "KYC analyst");
    }

    public synchronized void addDocument(String loanId, Map<String, Object> metadata) {
        Map<String, Object> loan = findLoan(loanId);
        List<Map<String, Object>> documents = objectMapList(loan.getOrDefault("documents", List.of()));
        String type = required(metadata, "type");
        String objectRef = required(metadata, "objectRef");
        if (documents.stream().anyMatch(item -> Objects.equals(item.get("objectRef"), objectRef))) return;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", String.valueOf(metadata.getOrDefault("id", "doc-" + (documents.size() + 1))));
        document.put("type", type);
        document.put("objectRef", objectRef);
        document.put("name", metadata.getOrDefault("name", objectRef));
        document.put("addedAt", Instant.now().toString());
        document.put("verified", false);
        document.put("scanStatus", "PENDING");

        documents.add(document);
        loan.put("documents", documents);
        addTimeline(loan, "Document reference received", type + " is awaiting secure scanning.", "Applicant");
        addAudit("Loan document reference registered", loanId + ":" + type, "Applicant");
    }

    public Map<String, Object> createLoan(Map<String, Object> payload) {
        String vehicleId = required(payload, "vehicleId");
        Map<String, Object> vehicle = findVehicle(vehicleId);
        Map<String, Object> dealer = findDealer(String.valueOf(vehicle.get("dealerId")));
        if (!Boolean.TRUE.equals(vehicle.get("available")) || !Boolean.TRUE.equals(vehicle.get("eligible"))) {
            throw new IllegalArgumentException("Vehicle is unavailable or not finance eligible.");
        }
        if (!"ACTIVE".equals(dealer.get("status"))) throw new IllegalArgumentException("Only active dealers can originate applications.");
        if (!Objects.equals(vehicle.get("priceVersion"), payload.get("priceVersion"))) {
            throw new IllegalStateException("Vehicle price changed. Refresh the selection before continuing.");
        }
        double downPayment = number(payload, "downPayment");
        int tenureMonths = (int) number(payload, "tenureMonths");
        double income = number(payload, "income");
        double obligations = number(payload, "obligations");
        if (downPayment < 0 || downPayment >= ((Number) vehicle.get("price")).doubleValue()) {
            throw new IllegalArgumentException("Down payment must be non-negative and less than vehicle price.");
        }
        if (tenureMonths < 6 || tenureMonths > 84 || income <= 0 || obligations < 0) {
            throw new IllegalArgumentException("Loan terms are outside the permitted range.");
        }

        String applicationId = nextLoanId();
        String now = Instant.now().toString();
        Map<String, Object> loan = map(
                "id", applicationId, "customerId", customers.get(0).get("id"),
                "applicantName", customers.get(0).get("firstName") + " " + customers.get(0).get("lastName"),
                "dealerId", dealer.get("id"), "vehicleId", vehicleId, "priceVersion", vehicle.get("priceVersion"),
                "downPayment", downPayment, "tenureMonths", tenureMonths, "income", income,
                "obligations", obligations, "employment", payload.getOrDefault("employment", "Other"),
                "status", "DRAFT", "kycStatus", customers.get(0).get("kycStatus"),
                "createdAt", now, "updatedAt", now, "source", payload.getOrDefault("source", "Direct"),
                "documents", payload.getOrDefault("documents", List.of()), "timeline", new ArrayList<>(),
                "policyVersion", "VFS-CREDIT-2026.10"
        );
        addTimeline(loan, "Draft created", "Application draft saved.", "Applicant");
        loans.add(0, loan);
        addAudit("Loan draft created", applicationId, "Applicant");
        return Map.of("applicationId", applicationId, "status", "DRAFT");
    }

    public synchronized void submitLoan(String loanId) {
        Map<String, Object> loan = findLoan(loanId);
        if (List.of("KYC_PENDING", "CREDIT_REVIEW").contains(loan.get("status"))) return;
        requireStatus(loan, "DRAFT");
        String nextStatus = "VERIFIED".equals(customers.get(0).get("kycStatus")) ? "CREDIT_REVIEW" : "KYC_PENDING";
        loan.put("status", nextStatus);
        loan.put("kycStatus", customers.get(0).get("kycStatus"));
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Application submitted", "Submitted for review by the lending team.", "Applicant");
        addAudit("Loan submitted", loanId, "Applicant");
    }

    public Map<String, Object> getLoan(String loanId) {
        return findLoan(loanId);
    }

    public Map<String, Object> loansResponse(String status, String customerId, int page, int size) {
        return loansResponse(status, customerId, null, null, page, size);
    }

    public Map<String, Object> loansResponse(String status, String customerId, String dealerId, String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> filtered = loans.stream()
                .filter(item -> status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) || Objects.equals(item.get("status"), status))
                .filter(item -> customerId == null || customerId.isBlank() || Objects.equals(item.get("customerId"), customerId))
                .filter(item -> dealerId == null || dealerId.isBlank() || Objects.equals(item.get("dealerId"), dealerId))
                .filter(item -> query == null || query.isBlank() || (String.valueOf(item.get("id")) + " " + String.valueOf(item.get("applicantName"))).toLowerCase().contains(query.toLowerCase()))
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("updatedAt")), Comparator.reverseOrder()))
                .toList();
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return map("items", filtered.subList(from, to), "total", filtered.size(), "page", safePage, "size", safeSize);
    }

    public synchronized Map<String, Object> updateDraft(String loanId, Map<String, Object> payload) {
        Map<String, Object> loan = findLoan(loanId);
        requireStatus(loan, "DRAFT");
        String vehicleId = required(payload, "vehicleId");
        Map<String, Object> vehicle = findVehicle(vehicleId);
        Map<String, Object> dealer = findDealer(String.valueOf(vehicle.get("dealerId")));
        if (!Boolean.TRUE.equals(vehicle.get("available")) || !Boolean.TRUE.equals(vehicle.get("eligible")) || !"ACTIVE".equals(dealer.get("status"))) {
            throw new IllegalArgumentException("Selected vehicle or dealer is not eligible.");
        }
        if (!Objects.equals(vehicle.get("priceVersion"), payload.get("priceVersion"))) throw new IllegalStateException("Vehicle price changed. Refresh the selection.");
        double downPayment = number(payload, "downPayment");
        int tenureMonths = (int) number(payload, "tenureMonths");
        double income = number(payload, "income");
        double obligations = number(payload, "obligations");
        if (downPayment < 0 || downPayment >= ((Number) vehicle.get("price")).doubleValue() || tenureMonths < 6 || tenureMonths > 84 || income <= 0 || obligations < 0) {
            throw new IllegalArgumentException("Loan terms are outside the permitted range.");
        }
        loan.put("vehicleId", vehicleId);
        loan.put("dealerId", dealer.get("id"));
        loan.put("priceVersion", vehicle.get("priceVersion"));
        loan.put("downPayment", downPayment);
        loan.put("tenureMonths", tenureMonths);
        loan.put("income", income);
        loan.put("obligations", obligations);
        loan.put("employment", payload.getOrDefault("employment", "Other"));
        loan.put("documents", payload.getOrDefault("documents", List.of()));
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Draft updated", "Application draft saved.", "Applicant");
        addAudit("Loan draft updated", loanId, "Applicant");
        return loan;
    }

    public Map<String, Object> getVehicle(String vehicleId) { return findVehicle(vehicleId); }

    public synchronized Map<String, Object> createVehicle(Map<String, Object> payload) {
        String make = required(payload, "make");
        String model = required(payload, "model");
        String variant = required(payload, "variant");
        String dealerId = required(payload, "dealerId");
        Map<String, Object> dealer = findDealer(dealerId);
        if (!"ACTIVE".equals(dealer.get("status"))) throw new IllegalArgumentException("Vehicle catalogue entries require an active dealer.");
        double price = number(payload, "price");
        int year = integer(payload.get("year"), 0);
        if (price <= 0 || year < 2000 || year > 2028) throw new IllegalArgumentException("Vehicle price or model year is invalid.");
        String id = "VEH-" + String.format("%04d", 1007 + vehicles.size() - 6);
        String priceVersion = "PV-26-" + String.format("%03d", audit.size() + 100);
        Map<String, Object> vehicle = map("id", id, "make", make, "model", model, "variant", variant,
                "year", year, "body", payload.getOrDefault("body", "Other"),
                "fuel", payload.getOrDefault("fuel", "Petrol"), "transmission", payload.getOrDefault("transmission", "Manual"),
                "price", price, "dealerId", dealerId, "available", Boolean.TRUE.equals(payload.getOrDefault("available", true)),
                "eligible", Boolean.TRUE.equals(payload.getOrDefault("eligible", false)),
                "stock", payload.getOrDefault("stock", "In stock"), "priceVersion", priceVersion,
                "color", payload.getOrDefault("color", "#b8d3d1"), "accent", payload.getOrDefault("accent", "#42666b"));
        vehicles.add(vehicle);
        addAudit("Vehicle catalogue entry created", id + ":" + priceVersion, "Catalogue administrator");
        return vehicle;
    }

    public Map<String, Object> getOffer(String offerId) {
        return loans.stream().map(item -> item.get("offer"))
                .filter(Map.class::isInstance).map(this::asStringMap)
                .filter(item -> Objects.equals(item.get("id"), offerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
    }

    public Map<String, Object> findLoanByOffer(String offerId) {
        return loans.stream()
                .filter(loan -> loan.get("offer") instanceof Map<?, ?> offer && Objects.equals(offer.get("id"), offerId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Offer not found."));
    }

    public Map<String, Object> auditResponse() { return map("items", List.copyOf(audit), "total", audit.size()); }

    public List<Map<String, Object>> getTimeline(String loanId) {
        Map<String, Object> loan = findLoan(loanId);
        return objectMapList(loan.getOrDefault("timeline", List.of()));
    }

    public void decideLoan(String loanId, Map<String, Object> body) {
        Map<String, Object> loan = findLoan(loanId);
        requireStatus(loan, "CREDIT_REVIEW");
        if (!"VERIFIED".equals(loan.get("kycStatus"))) throw new IllegalArgumentException("KYC must be verified before final decision.");
        String outcome = required(body, "outcome");
        String reasonCode = required(body, "reasonCode");
        String policyVersion = String.valueOf(body.getOrDefault("policyVersion", "VFS-CREDIT-2026.10"));
        Map<String, Object> vehicle = findVehicle(String.valueOf(loan.get("vehicleId")));
        Map<String, Object> dealer = findDealer(String.valueOf(loan.get("dealerId")));
        if (!Boolean.TRUE.equals(vehicle.get("available")) || !Objects.equals(vehicle.get("priceVersion"), loan.get("priceVersion"))) {
            throw new IllegalArgumentException("Vehicle price or availability is stale; reassessment is required.");
        }
        if (!"ACTIVE".equals(dealer.get("status"))) throw new IllegalArgumentException("Dealer is no longer active.");
        loan.put("decisionReason", reasonCode);
        loan.put("policyVersion", policyVersion);
        if ("APPROVE".equals(outcome)) {
            double amount = ((Number) vehicle.get("price")).doubleValue() - ((Number) loan.get("downPayment")).doubleValue();
            String loanIdValue = String.valueOf(loan.get("id"));
            String offerId = "OFF-" + loanIdValue.substring(loanIdValue.lastIndexOf('-') + 1);
            loan.put("offer", map("id", offerId, "version", 1, "amount", amount, "annualRate", 10.5,
                    "tenureMonths", loan.get("tenureMonths"), "fees", 6500,
                    "expiresAt", Instant.now().plusSeconds(14L * 86400).toString(), "acceptedAt", null,
                    "disclosureVersion", "VFS-OFFER-2026.10",
                        "conditions", new ArrayList<>(List.of(
                            map("id", "kyc", "label", "KYC verified", "done", true),
                            map("id", "dealer", "label", "Dealer verified", "done", true),
                            map("id", "agreement", "label", "Signed agreement", "done", false),
                            map("id", "mandate", "label", "Mandate registered", "done", false)))));
            loan.put("status", "APPROVED_PENDING_ACCEPTANCE");
        } else if ("DECLINE".equals(outcome)) {
            loan.put("status", "DECLINED");
        } else if ("MORE_INFO".equals(outcome)) {
            loan.put("status", "MORE_INFO_REQUIRED");
        } else {
            throw new IllegalArgumentException("Unsupported decision outcome.");
        }
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Decision recorded", "Outcome: " + outcome + ". Reason: " + reasonCode, "Approver");
        addAudit("Loan decision recorded", loanId + ":" + outcome + ":" + policyVersion, "Approver");
    }

    public synchronized void recordKycDecision(String loanId, Map<String, Object> body) {
        Map<String, Object> loan = findLoan(loanId);
        requireOneOfStatuses(loan, "KYC_PENDING", "KYC_REWORK");
        String outcome = required(body, "outcome");
        String reason = String.valueOf(body.getOrDefault("reason", ""));
        if (!"VERIFIED".equals(outcome) && reason.isBlank()) throw new IllegalArgumentException("A reason is required for rework or rejection.");
        String nextStatus = switch (outcome) {
            case "VERIFIED" -> "CREDIT_REVIEW";
            case "REWORK" -> "KYC_REWORK";
            case "REJECTED" -> "KYC_REJECTED";
            default -> throw new IllegalArgumentException("Unsupported KYC outcome.");
        };
        loan.put("kycStatus", outcome);
        loan.put("status", nextStatus);
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "KYC " + outcome.toLowerCase(), reason.isBlank() ? "Identity evidence verified." : reason, "KYC analyst");
        addAudit("KYC decision recorded", loanId + ":" + outcome, "KYC analyst");
    }

    public synchronized void recordRecommendation(String loanId, Map<String, Object> body) {
        Map<String, Object> loan = findLoan(loanId);
        requireStatus(loan, "CREDIT_REVIEW");
        if (!"VERIFIED".equals(loan.get("kycStatus"))) throw new IllegalArgumentException("KYC must be verified before credit review.");
        String outcome = required(body, "outcome");
        String reason = required(body, "reason");
        if (!List.of("APPROVE", "DECLINE", "MORE_INFO").contains(outcome)) throw new IllegalArgumentException("Unsupported recommendation.");
        loan.put("recommendation", outcome);
        loan.put("note", reason);
        if ("MORE_INFO".equals(outcome)) loan.put("status", "MORE_INFO_REQUIRED");
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Credit recommendation recorded", reason, "Credit analyst");
        addAudit("Credit recommendation", loanId + ":" + outcome, "Credit analyst");
    }

    public synchronized void acceptOffer(String offerId, Integer offerVersion) {
        acceptOffer(offerId, offerVersion, "VFS-OFFER-2026.10", true, "Demo customer");
    }

    public synchronized Map<String, Object> acceptSignedAgreement(String offerId, Integer offerVersion, String disclosureVersion,
                                                                  String customerId, String agreementId) {
        Map<String, Object> loan = loans.stream()
                .filter(item -> item.get("offer") instanceof Map<?, ?> offer && Objects.equals(offer.get("id"), offerId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        requireStatus(loan, "CONDITIONS_PENDING");
        if (!Objects.equals(loan.get("customerId"), customerId)) throw new IllegalStateException("Agreement customer does not own this application.");
        Map<String, Object> offer = asStringMap(loan.get("offer"));
        if (!Objects.equals(((Number) offer.get("version")).intValue(), offerVersion)) throw new IllegalStateException("Offer version changed before signature completion.");
        Map<String, Object> condition = getConditions(offer).stream().filter(item -> Objects.equals(item.get("id"), "agreement")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Offer has no agreement condition."));
        condition.put("done", true);
        offer.put("agreementId", agreementId);
        offer.put("disclosureVersion", disclosureVersion);
        if (getConditions(offer).stream().allMatch(item -> Boolean.TRUE.equals(item.get("done")))) loan.put("status", "READY_FOR_FUNDING");
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Agreement signed", "Agreement signed for disclosure version " + disclosureVersion + ".", "Applicant");
        addAudit("Agreement signature recorded", String.valueOf(loan.get("id")) + ":" + agreementId, "Applicant");
        return loan;
    }

    public synchronized void acceptOffer(String offerId, Integer offerVersion, String consentVersion, boolean consentAccepted, String acceptedBy) {
        Map<String, Object> loan = loans.stream()
                .filter(item -> item.get("offer") instanceof Map<?, ?> offer && Objects.equals(offer.get("id"), offerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        Map<String, Object> offer = asStringMap(loan.get("offer"));
        if (!consentAccepted) throw new IllegalArgumentException("Explicit offer consent is required.");
        if (consentVersion == null || consentVersion.isBlank()) throw new IllegalArgumentException("Offer disclosure version is required.");
        if (!Objects.equals(((Number) offer.get("version")).intValue(), offerVersion)) throw new IllegalStateException("Offer version is stale.");
        if (offer.get("acceptedAt") != null && List.of("CONDITIONS_PENDING", "READY_FOR_FUNDING", "FUNDING_REQUESTED", "FUNDED").contains(loan.get("status"))) return;
        requireStatus(loan, "APPROVED_PENDING_ACCEPTANCE");
        if (Instant.parse(String.valueOf(offer.get("expiresAt"))).isBefore(Instant.now())) throw new IllegalStateException("Offer has expired.");
        offer.put("acceptedAt", Instant.now().toString());
        offer.put("consentVersion", consentVersion);
        offer.put("acceptedBy", acceptedBy == null || acceptedBy.isBlank() ? "Demo customer" : acceptedBy);
        loan.put("status", "CONDITIONS_PENDING");
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Offer accepted", "Customer accepted offer version " + offerVersion + ".", "Applicant");
        addAudit("Offer accepted", offerId + ":" + offerVersion, "Applicant");
    }

    public synchronized void updateCondition(String loanId, String conditionId, boolean done) {
        Map<String, Object> loan = findLoan(loanId);
        requireOneOfStatuses(loan, "CONDITIONS_PENDING", "READY_FOR_FUNDING");
        Map<String, Object> offer = asStringMap(loan.get("offer"));
        if (offer == null || offer.get("acceptedAt") == null) throw new IllegalArgumentException("Accepted offer required.");
        List<Map<String, Object>> conditions = getConditions(offer);
        Map<String, Object> condition = conditions.stream().filter(item -> Objects.equals(item.get("id"), conditionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Condition not found."));
        condition.put("done", done);
        loan.put("status", conditions.stream().allMatch(item -> Boolean.TRUE.equals(item.get("done"))) ? "READY_FOR_FUNDING" : "CONDITIONS_PENDING");
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Condition updated", String.valueOf(condition.get("label")), "Operations");
        addAudit("Offer condition updated", loanId + ":" + conditionId, "Operations");
    }

    public synchronized FundingGateway.FundingReceipt requestFunding(String loanId, String idempotencyKey, FundingGateway gateway) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required for funding requests.");
        String keyHash = sha256(idempotencyKey.trim());
        Map<String, Object> prior = fundingRequests.stream()
                .filter(item -> Objects.equals(item.get("idempotencyKeyHash"), keyHash))
                .findFirst().orElse(null);
        if (prior != null) {
            if (!Objects.equals(prior.get("loanId"), loanId)) throw new IllegalStateException("Idempotency-Key was already used for a different loan.");
            return fundingReceipt(prior);
        }
        Map<String, Object> loan = findLoan(loanId);
        if ("FUNDING_REQUESTED".equals(loan.get("status"))) throw new IllegalStateException("Funding was already requested for this loan; retry using its original Idempotency-Key.");
        requireStatus(loan, "READY_FOR_FUNDING");
        Map<String, Object> offer = asStringMap(loan.get("offer"));
        List<Map<String, Object>> conditions = getConditions(offer);
        if (offer.get("acceptedAt") == null || conditions.stream().anyMatch(item -> !Boolean.TRUE.equals(item.get("done")))) {
            throw new IllegalArgumentException("Accepted offer and completed conditions are required.");
        }
        long amountMinorUnits = Math.round(((Number) offer.get("amount")).doubleValue() * 100);
        if (amountMinorUnits <= 0) throw new IllegalStateException("Offer amount is invalid for funding.");
        FundingGateway.FundingReceipt receipt = gateway.submit(idempotencyKey.trim(),
                new FundingGateway.FundingInstruction(loanId, String.valueOf(offer.get("id")), amountMinorUnits));
        if (!"ACKNOWLEDGED".equals(receipt.status()) || receipt.disbursed()) {
            throw new IllegalStateException("Funding provider returned an unsupported acknowledgement state.");
        }
        Map<String, Object> record = map(
                "loanId", loanId,
                "offerId", offer.get("id"),
                "idempotencyKeyHash", keyHash,
                "requestId", receipt.requestId(),
                "provider", receipt.provider(),
                "providerReference", receipt.providerReference(),
                "status", receipt.status(),
                "disbursed", receipt.disbursed(),
                "amountMinorUnits", amountMinorUnits,
                "requestedAt", Instant.now().toString());
        fundingRequests.add(record);
        loan.put("status", "FUNDING_REQUESTED");
        loan.put("fundingRequestId", receipt.requestId());
        loan.put("fundingProvider", receipt.provider());
        loan.put("fundingProviderReference", receipt.providerReference());
        loan.put("fundingStatus", receipt.status());
        loan.put("fundsDisbursed", false);
        loan.put("updatedAt", Instant.now().toString());
        addTimeline(loan, "Funding provider acknowledged request", receipt.provider() + " acknowledged the hand-off as " + receipt.providerReference() + "; no funds were disbursed.", "Operations");
        addAudit("Funding provider acknowledged request", loanId + ":" + receipt.requestId(), "Operations");
        return receipt;
    }

    private FundingGateway.FundingReceipt fundingReceipt(Map<String, Object> stored) {
        return new FundingGateway.FundingReceipt(String.valueOf(stored.get("requestId")), String.valueOf(stored.get("provider")),
                String.valueOf(stored.get("providerReference")), String.valueOf(stored.get("status")), Boolean.TRUE.equals(stored.get("disbursed")));
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Could not hash funding idempotency key.", exception); }
    }

    public synchronized void updateDealerStatus(String dealerId, String status) {
        Map<String, Object> dealer = findDealer(dealerId);
        if (!List.of("ACTIVE", "PENDING", "SUSPENDED", "REJECTED").contains(status)) throw new IllegalArgumentException("Unsupported dealer status.");
        dealer.put("status", status);
        addAudit("Dealer status updated", dealerId + ":" + status, "Authorized reviewer");
    }

    public synchronized Map<String, Object> updateVehicle(String vehicleId, Map<String, Object> payload) {
        Map<String, Object> vehicle = findVehicle(vehicleId);
        if (payload.containsKey("available")) vehicle.put("available", Boolean.TRUE.equals(payload.get("available")));
        if (payload.containsKey("eligible")) vehicle.put("eligible", Boolean.TRUE.equals(payload.get("eligible")));
        if (payload.containsKey("stock")) vehicle.put("stock", required(payload, "stock"));
        if (payload.containsKey("price")) {
            double newPrice = number(payload, "price");
            if (newPrice <= 0) throw new IllegalArgumentException("Vehicle price must be positive.");
            if (Double.compare(((Number) vehicle.get("price")).doubleValue(), newPrice) != 0) {
                vehicle.put("price", newPrice);
                String oldVersion = String.valueOf(vehicle.get("priceVersion"));
                vehicle.put("priceVersion", oldVersion + "-R" + (audit.size() + 1));
                addAudit("Vehicle price changed", vehicleId + ":" + oldVersion + "→" + vehicle.get("priceVersion"), "Catalogue administrator");
            }
        }
        return vehicle;
    }

    public Map<String, Object> searchVehicles(Map<String, Object> filters) {
        List<Map<String, Object>> matched = vehicles.stream()
                .filter(item -> filterMatches(item, filters))
                .toList();
        int page = filters == null ? 0 : Math.max(0, integer(filters.get("page"), 0));
        int size = filters == null ? matched.size() : Math.max(1, Math.min(100, integer(filters.get("size"), 100)));
        int from = Math.min(page * size, matched.size());
        int to = Math.min(from + size, matched.size());
        return map("items", matched.subList(from, to), "total", matched.size(), "page", page, "size", size);
    }

    private boolean filterMatches(Map<String, Object> vehicle, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        String search = String.valueOf(filters.getOrDefault("q", "")).trim();
        if (!search.isEmpty()) {
            String combined = (String.valueOf(vehicle.getOrDefault("make", "")) + " " +
                    String.valueOf(vehicle.getOrDefault("model", "")) + " " +
                    String.valueOf(vehicle.getOrDefault("variant", ""))).toLowerCase();
            if (!combined.contains(search.toLowerCase())) {
                return false;
            }
        }
        for (String field : List.of("make", "model", "body", "fuel", "transmission", "dealerId")) {
            Object requested = filters.get(field);
            if (requested != null && !String.valueOf(requested).isBlank()
                && !String.valueOf(requested).equalsIgnoreCase("All")
                && !String.valueOf(requested).equalsIgnoreCase(String.valueOf(vehicle.get(field)))) return false;
        }
        if (filters.containsKey("available") && !Objects.equals(vehicle.get("available"), filters.get("available"))) return false;
        if (filters.containsKey("eligible") && !Objects.equals(vehicle.get("eligible"), filters.get("eligible"))) return false;
        if (filters.containsKey("minPrice") && ((Number) vehicle.get("price")).doubleValue() < number(filters, "minPrice")) return false;
        if (filters.containsKey("maxPrice") && ((Number) vehicle.get("price")).doubleValue() > number(filters, "maxPrice")) return false;
        if (filters.containsKey("year") && !Objects.equals(((Number) vehicle.get("year")).intValue(), integer(filters.get("year"), -1))) return false;
        return true;
    }

    private void seed() {
        dealers.add(map("id", "D-1042", "name", "Northstar Motors", "region", "South", "status", "ACTIVE", "branches", 4, "contact", "dealer@example.test", "address", "Anna Salai, Chennai"));
        dealers.add(map("id", "D-1043", "name", "Eastline Automotive", "region", "South", "status", "ACTIVE", "branches", 6, "contact", "eastline@example.test", "address", "OMR, Chennai"));
        dealers.add(map("id", "D-1044", "name", "Horizon Mobility", "region", "West", "status", "ACTIVE", "branches", 3, "contact", "horizon@example.test", "address", "Bengaluru, Karnataka"));
        dealers.add(map("id", "D-1045", "name", "Metro Auto Group", "region", "North", "status", "PENDING", "branches", 2, "contact", "metro@example.test", "address", "New Delhi"));
        dealers.add(map("id", "D-1046", "name", "Pioneer Wheels", "region", "South", "status", "SUSPENDED", "branches", 1, "contact", "pioneer@example.test", "address", "Coimbatore, Tamil Nadu"));

        for (Map<String, Object> dealer : dealers) {
            int branchCount = ((Number) dealer.get("branches")).intValue();
            for (int index = 1; index <= branchCount; index++) {
                dealerBranches.add(map("id", dealer.get("id") + "-B" + String.format("%02d", index),
                        "dealerId", dealer.get("id"), "name", index == 1 ? dealer.get("name") + " - Main" : dealer.get("name") + " - Branch " + index,
                        "address", dealer.get("address"), "city", dealer.get("region"), "region", dealer.get("region"),
                        "status", "ACTIVE".equals(dealer.get("status")) ? "ACTIVE" : "PENDING"));
            }
        }
        dealerStaff.add(map("id", "D-1042-U001", "dealerId", "D-1042", "name", "Jordan Lee", "email", "jordan.lee@example.test", "role", "DEALER_ADMIN", "status", "ACTIVE"));
        dealerStaff.add(map("id", "D-1042-U002", "dealerId", "D-1042", "name", "Riya Patel", "email", "riya.patel@example.test", "role", "DEALER_USER", "status", "ACTIVE"));
        dealerStaff.add(map("id", "D-1043-U001", "dealerId", "D-1043", "name", "Aarav Kumar", "email", "aarav.kumar@example.test", "role", "DEALER_ADMIN", "status", "ACTIVE"));

        vehicles.add(map("id", "VEH-1001", "make", "Hyundai", "model", "Creta", "variant", "SX(O) 1.5 Diesel", "year", 2026, "body", "SUV", "fuel", "Diesel", "transmission", "Automatic", "price", 1850000, "dealerId", "D-1042", "available", true, "eligible", true, "stock", "In stock", "priceVersion", "PV-26-041", "color", "#aeced0", "accent", "#324f60"));
        vehicles.add(map("id", "VEH-1002", "make", "Honda", "model", "City", "variant", "ZX CVT", "year", 2026, "body", "Sedan", "fuel", "Petrol", "transmission", "Automatic", "price", 1640000, "dealerId", "D-1043", "available", true, "eligible", true, "stock", "3 available", "priceVersion", "PV-26-025", "color", "#c5d6e7", "accent", "#526c90"));
        vehicles.add(map("id", "VEH-1003", "make", "Tata", "model", "Nexon", "variant", "Creative+ S", "year", 2026, "body", "SUV", "fuel", "Petrol", "transmission", "Manual", "price", 1290000, "dealerId", "D-1042", "available", true, "eligible", true, "stock", "5 available", "priceVersion", "PV-26-034", "color", "#e0c5ab", "accent", "#795c48"));
        vehicles.add(map("id", "VEH-1004", "make", "Mahindra", "model", "XUV 3XO", "variant", "AX7 Luxury", "year", 2026, "body", "SUV", "fuel", "Petrol", "transmission", "Automatic", "price", 1575000, "dealerId", "D-1044", "available", true, "eligible", true, "stock", "2 available", "priceVersion", "PV-26-052", "color", "#b7c9d5", "accent", "#546c7f"));
        vehicles.add(map("id", "VEH-1005", "make", "Tata", "model", "Tiago EV", "variant", "XZ+ Long Range", "year", 2026, "body", "Hatchback", "fuel", "Electric", "transmission", "Automatic", "price", 1180000, "dealerId", "D-1043", "available", true, "eligible", true, "stock", "Available", "priceVersion", "PV-26-057", "color", "#b9d6cb", "accent", "#416b61"));
        vehicles.add(map("id", "VEH-1006", "make", "Maruti Suzuki", "model", "Grand Vitara", "variant", "Alpha+", "year", 2026, "body", "SUV", "fuel", "Hybrid", "transmission", "Automatic", "price", 2040000, "dealerId", "D-1044", "available", false, "eligible", true, "stock", "Out of stock", "priceVersion", "PV-26-018", "color", "#d3c8d4", "accent", "#645a72"));

        customers.add(map("id", "C-1001", "firstName", "Alex", "lastName", "Morgan", "email", "alex.morgan@example.test", "mobile", "9000000001", "kycStatus", "VERIFIED", "consent", true));

        Map<String, Object> loan1 = new LinkedHashMap<>();
        loan1.put("id", "VF-2026-00142");
        loan1.put("customerId", "C-1001");
        loan1.put("applicantName", "Alex Morgan");
        loan1.put("dealerId", "D-1042");
        loan1.put("vehicleId", "VEH-1001");
        loan1.put("priceVersion", "PV-26-041");
        loan1.put("downPayment", 350000);
        loan1.put("tenureMonths", 48);
        loan1.put("income", 90000);
        loan1.put("obligations", 12000);
        loan1.put("employment", "Salaried");
        loan1.put("status", "CREDIT_REVIEW");
        loan1.put("kycStatus", "VERIFIED");
        loan1.put("createdAt", "2026-10-12T10:30:00+05:30");
        loan1.put("updatedAt", "2026-10-25T10:30:00+05:30");
        loan1.put("source", "Direct");
        loan1.put("documents", List.of(
                map("id", "doc-1", "type", "Identity proof", "name", "identity-proof.pdf", "addedAt", "2026-10-20T10:30:00+05:30", "verified", true),
                map("id", "doc-2", "type", "Address proof", "name", "address-proof.pdf", "addedAt", "2026-10-20T10:30:00+05:30", "verified", true),
                map("id", "doc-3", "type", "Income proof", "name", "income-proof.pdf", "addedAt", "2026-10-20T10:30:00+05:30", "verified", true)
        ));
        loan1.put("timeline", List.of(
                map("id", "a1", "title", "Application created", "description", "Draft saved.", "at", "2026-10-12T10:30:00+05:30", "actor", "Applicant"),
                map("id", "a2", "title", "Submitted", "description", "Application sent for review.", "at", "2026-10-19T10:30:00+05:30", "actor", "Applicant"),
                map("id", "a3", "title", "KYC verified", "description", "Identity evidence accepted.", "at", "2026-10-21T10:30:00+05:30", "actor", "KYC analyst"),
                map("id", "a4", "title", "Credit review", "description", "Your application is now being assessed.", "at", "2026-10-24T10:30:00+05:30", "actor", "System")
        ));
        loan1.put("policyVersion", "VFS-CREDIT-2026.10");
        loans.add(loan1);

        Map<String, Object> loan2 = new LinkedHashMap<>();
        loan2.put("id", "VF-2026-00136");
        loan2.put("customerId", "C-1001");
        loan2.put("applicantName", "Alex Morgan");
        loan2.put("dealerId", "D-1043");
        loan2.put("vehicleId", "VEH-1002");
        loan2.put("priceVersion", "PV-26-025");
        loan2.put("downPayment", 440000);
        loan2.put("tenureMonths", 48);
        loan2.put("income", 90000);
        loan2.put("obligations", 12000);
        loan2.put("employment", "Salaried");
        loan2.put("status", "APPROVED_PENDING_ACCEPTANCE");
        loan2.put("kycStatus", "VERIFIED");
        loan2.put("createdAt", "2026-10-08T10:30:00+05:30");
        loan2.put("updatedAt", "2026-10-25T10:30:00+05:30");
        loan2.put("source", "Dealer");
        loan2.put("documents", List.of(
                map("id", "d-1", "type", "Identity proof", "name", "identity-proof.pdf", "addedAt", "2026-10-08T10:30:00+05:30", "verified", true),
                map("id", "d-2", "type", "Address proof", "name", "address-proof.pdf", "addedAt", "2026-10-08T10:30:00+05:30", "verified", true),
                map("id", "d-3", "type", "Income proof", "name", "income-proof.pdf", "addedAt", "2026-10-08T10:30:00+05:30", "verified", true)
        ));
        loan2.put("offer", map(
                "id", "OFF-10036",
                "version", 1,
                "amount", 1200000,
                "annualRate", 10.5,
                "tenureMonths", 48,
                "fees", 6500,
                "expiresAt", "2026-11-15T23:59:00+05:30",
                "disclosureVersion", "VFS-OFFER-2026.10",
                "acceptedAt", null,
                "conditions", new ArrayList<>(List.of(
                        map("id", "kyc", "label", "KYC verified", "done", true),
                        map("id", "dealer", "label", "Dealer verified", "done", true),
                        map("id", "agreement", "label", "Signed agreement", "done", false)
                ))
        ));
        loan2.put("timeline", List.of(
                map("id", "b1", "title", "Application submitted", "description", "Successfully received.", "at", "2026-10-08T10:30:00+05:30", "actor", "Applicant"),
                map("id", "b2", "title", "KYC verified", "description", "Identity review completed.", "at", "2026-10-14T10:30:00+05:30", "actor", "KYC analyst"),
                map("id", "b3", "title", "Loan approved", "description", "Your finance offer is ready.", "at", "2026-10-24T10:30:00+05:30", "actor", "Approver")
        ));
        loan2.put("policyVersion", "VFS-CREDIT-2026.10");
        loan2.put("recommendation", "APPROVE");
        loans.add(loan2);

        Map<String, Object> loan3 = new LinkedHashMap<>();
        loan3.put("id", "VF-2026-00118");
        loan3.put("customerId", "C-1001");
        loan3.put("applicantName", "Alex Morgan");
        loan3.put("dealerId", "D-1043");
        loan3.put("vehicleId", "VEH-1005");
        loan3.put("priceVersion", "PV-26-057");
        loan3.put("downPayment", 250000);
        loan3.put("tenureMonths", 60);
        loan3.put("income", 90000);
        loan3.put("obligations", 12000);
        loan3.put("employment", "Salaried");
        loan3.put("status", "DRAFT");
        loan3.put("kycStatus", "VERIFIED");
        loan3.put("createdAt", "2026-10-05T10:30:00+05:30");
        loan3.put("updatedAt", "2026-10-25T10:30:00+05:30");
        loan3.put("source", "Direct");
        loan3.put("documents", List.of());
        loan3.put("timeline", List.of(map("id", "c1", "title", "Draft created", "description", "Continue whenever you are ready.", "at", "2026-10-05T10:30:00+05:30", "actor", "Applicant")));
        loan3.put("policyVersion", "VFS-CREDIT-2026.10");
        loans.add(loan3);

        Map<String, Object> loan4 = new LinkedHashMap<>();
        loan4.put("id", "VF-2026-00167");
        loan4.put("customerId", "C-1002");
        loan4.put("applicantName", "Maya Shah");
        loan4.put("dealerId", "D-1042");
        loan4.put("vehicleId", "VEH-1003");
        loan4.put("priceVersion", "PV-26-034");
        loan4.put("downPayment", 250000);
        loan4.put("tenureMonths", 48);
        loan4.put("income", 90000);
        loan4.put("obligations", 10000);
        loan4.put("employment", "Self-employed");
        loan4.put("status", "KYC_PENDING");
        loan4.put("kycStatus", "PENDING");
        loan4.put("createdAt", "2026-10-23T10:30:00+05:30");
        loan4.put("updatedAt", "2026-10-23T10:30:00+05:30");
        loan4.put("source", "Dealer");
        loan4.put("documents", List.of(map("id", "m1", "type", "Identity proof", "name", "identity-proof.pdf", "addedAt", "2026-10-23T10:30:00+05:30", "verified", false), map("id", "m2", "type", "Address proof", "name", "address-proof.pdf", "addedAt", "2026-10-23T10:30:00+05:30", "verified", false)));
        loan4.put("timeline", List.of(map("id", "d1", "title", "Application submitted", "description", "Awaiting identity verification.", "at", "2026-10-23T10:30:00+05:30", "actor", "Dealer user")));
        loan4.put("policyVersion", "VFS-CREDIT-2026.10");
        loans.add(loan4);

        audit.add(map("id", "audit1", "title", "Demo initialized", "description", "Fictional sample records seeded.", "at", "2026-10-21T10:30:00+05:30", "actor", "System"));
    }

    private Map<String, Object> findLoan(String loanId) {
        return loans.stream()
                .filter(item -> Objects.equals(item.get("id"), loanId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));
    }

    private Map<String, Object> findVehicle(String vehicleId) {
        return vehicles.stream()
                .filter(item -> Objects.equals(item.get("id"), vehicleId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));
    }

    private Map<String, Object> findDealer(String dealerId) {
        return dealers.stream()
                .filter(item -> Objects.equals(item.get("id"), dealerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found: " + dealerId));
    }

    private Map<String, Object> findKycCase(String caseId) {
        return kycCases.stream().filter(item -> Objects.equals(item.get("kycCaseId"), caseId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("KYC case not found."));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalStateException("Offer data is missing or malformed.");
        if (source.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new IllegalStateException("Offer data has invalid keys.");
        return (Map<String, Object>) source;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getConditions(Map<String, Object> offer) {
        Object value = offer.get("conditions");
        if (!(value instanceof List<?> source)) throw new IllegalStateException("Offer conditions are missing or malformed.");
        for (Object item : source) asStringMap(item);
        return (List<Map<String, Object>>) source;
    }

    private int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Expected an integer filter value."); }
    }

    private String required(Map<String, Object> payload, String field) {
        Object value = payload == null ? null : payload.get(field);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(field + " is required.");
        return String.valueOf(value).trim();
    }

    private double number(Map<String, Object> payload, String field) {
        Object value = payload == null ? null : payload.get(field);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(field + " must be numeric.");
        return number.doubleValue();
    }

    private void requireStatus(Map<String, Object> loan, String expected) {
        if (!Objects.equals(loan.get("status"), expected)) {
            throw new IllegalStateException("Invalid state transition from " + loan.get("status") + "; expected " + expected + ".");
        }
    }

    private void requireOneOfStatuses(Map<String, Object> loan, String... allowed) {
        if (List.of(allowed).stream().noneMatch(status -> Objects.equals(loan.get("status"), status))) {
            throw new IllegalArgumentException("This action is not permitted from state " + loan.get("status") + ".");
        }
    }

    private void requireOneOfValues(Object current, String... allowed) {
        if (List.of(allowed).stream().noneMatch(status -> Objects.equals(current, status))) {
            throw new IllegalStateException("This action is not permitted from state " + current + ".");
        }
    }

    private void addAudit(String title, String description, String actor) {
        audit.add(0, map("id", "audit-" + (audit.size() + 1), "title", title, "description", description,
                "at", Instant.now().toString(), "actor", actor));
    }

    private void addTimeline(Map<String, Object> loan, String title, String description, String actor) {
        List<Map<String, Object>> events = objectMapList(loan.getOrDefault("timeline", List.of()));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt-" + (events.size() + 1));
        event.put("title", title);
        event.put("description", description);
        event.put("at", Instant.now().toString());
        event.put("actor", actor);
        events.add(event);
        loan.put("timeline", events);
    }

    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof List<?> source)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : source) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entryValue) -> copy.put(String.valueOf(key), entryValue));
                result.add(copy);
            }
        }
        return result;
    }

    private Map<String, Object> map(Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }

    private String nextLoanId() {
        return "VF-2026-" + String.format("%05d", loans.size() + 1 + 10000);
    }
}
