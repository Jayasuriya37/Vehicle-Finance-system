package com.vehiclefinance.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.vehiclefinance.api.config.DocumentStorageProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatePersistenceIntegrationTest {
    @Autowired private StateSnapshotStore store;
    @Autowired private VehicleFinanceService service;
    @Autowired private DocumentStorageProperties documentStorageProperties;
    @Value("${local.server.port}") private int port;

    @Test
    void serviceSnapshotCanBeStoredAndRestoredFromJdbc() throws Exception {
        String requestBody = "{\"name\":\"Persistence Test Motors\",\"region\":\"Central\",\"contact\":\"persist@example.test\",\"address\":\"Synthetic test address\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/dealers"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());

        Map<String, Object> snapshot = store.load().orElseThrow();
        assertTrue(snapshot.containsKey("dealers"));
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> dealers = (List<Map<String,Object>>) snapshot.get("dealers");
        Map<String,Object> restoredDealer = dealers.stream().filter(dealer -> "Persistence Test Motors".equals(dealer.get("name"))).findFirst().orElseThrow();
        assertEquals("Persistence Test Motors", restoredDealer.get("name"));
        assertEquals("PENDING", restoredDealer.get("status"));

        VehicleFinanceService reloadedService = new VehicleFinanceService(store);
        assertEquals("Persistence Test Motors", reloadedService.getDealer(String.valueOf(restoredDealer.get("id"))).get("name"));
    }

    @Test
    void registrationRequiresBothEmailAndSmsOtpChallenges() throws Exception {
        String base = "http://localhost:" + port;
        String email = "otp-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String mobile = "+1415" + String.format("%07d", Math.abs(UUID.randomUUID().hashCode()) % 10_000_000);
        String profile = "{\"firstName\":\"OTP\",\"lastName\":\"Test\",\"email\":\"" + email + "\",\"mobile\":\"" + mobile + "\",\"consent\":true,\"consentVersion\":\"VFS-PRIVACY-1\"}";

        HttpResponse<String> blocked = post(base + "/api/customers", profile);
        assertEquals(409, blocked.statusCode());

        String emailChallenge = post(base + "/api/otp/challenges", "{\"channel\":\"EMAIL\",\"destination\":\"" + email + "\"}").body();
        String smsChallenge = post(base + "/api/otp/challenges", "{\"channel\":\"SMS\",\"destination\":\"" + mobile + "\"}").body();
        assertEquals(200, post(base + "/api/otp/challenges/verify", verifyBody(emailChallenge)).statusCode());

        HttpResponse<String> oneChannelOnly = post(base + "/api/customers", profile);
        assertEquals(409, oneChannelOnly.statusCode());

        assertEquals(200, post(base + "/api/otp/challenges/verify", verifyBody(smsChallenge)).statusCode());
        assertEquals(201, post(base + "/api/customers", profile).statusCode());
        assertEquals(409, post(base + "/api/customers", profile).statusCode(), "verified OTP proofs should be one-time");
    }

    @Test
    void multipartDocumentUploadIsStoredAndLinkedAsPendingScan() throws Exception {
        documentStorageProperties.setUploadEnabled(true);
        String caseId = String.valueOf(service.createKycCase(Map.of("customerId", "C-1001")).get("kycCaseId"));
        String boundary = "----vfs-test-" + UUID.randomUUID();
        byte[] pdf = "%PDF-1.7\nSynthetic test document".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        writePart(body, boundary, "ownerType", "KYC_CASE");
        writePart(body, boundary, "ownerId", caseId);
        writePart(body, boundary, "documentType", "Identity proof");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"identity.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        body.write(pdf);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.body().contains("quarantine/"));
        assertTrue(response.body().contains("PENDING"));
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> evidence = (List<Map<String,Object>>) service.getKycCase(caseId).get("evidence");
        assertEquals(1, evidence.size());
        assertEquals("PENDING", evidence.get(0).get("scanStatus"));
    }

    @Test
    void uploadRejectsUnsupportedDocumentCategoryAndMissingOwner() throws Exception {
        documentStorageProperties.setUploadEnabled(true);
        String boundary = "----vfs-test-" + UUID.randomUUID();
        byte[] pdf = "%PDF-1.7\nSynthetic test document".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String body = multipartBody(boundary, pdf, "UNKNOWN", "bad-category");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.ISO_8859_1)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(422, response.statusCode());
    }

    @Test
    void uploadIsDisabledUnlessExplicitlyEnabled() throws Exception {
        documentStorageProperties.setUploadEnabled(false);
        String boundary = "----vfs-disabled-" + UUID.randomUUID();
        String body = multipartBody(boundary, "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "KYC_CASE", "Identity proof");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.ISO_8859_1)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
    }

        @Test
        void acceptedOfferCanCompleteSandboxEsignAndMarkAgreementCondition() throws Exception {
        String base = "http://localhost:" + port;
        String offerId = "OFF-10036";
        String loanId = "VF-2026-00136";
        String disclosure = "VFS-OFFER-2026.10";
        assertEquals(204, post(base + "/api/offers/" + offerId + "/accept",
            "{\"offerVersion\":1,\"consentVersion\":\"" + disclosure + "\",\"consentAccepted\":true,\"acceptedBy\":\"C-1001\"}").statusCode());
        HttpResponse<String> started = post(base + "/api/offers/" + offerId + "/esign-sessions",
            "{\"offerVersion\":1,\"disclosureVersion\":\"" + disclosure + "\",\"customerId\":\"C-1001\"}");
        assertEquals(202, started.statusCode(), started.body());
        String sessionId = jsonString(started.body(), "sessionId");
        assertEquals(200, post(base + "/api/esign-sessions/" + sessionId + "/complete",
            "{\"consentAccepted\":true,\"disclosureVersion\":\"" + disclosure + "\"}").statusCode());
        assertEquals(200, post(base + "/api/loans/" + loanId + "/agreement-signed",
            "{\"sessionId\":\"" + sessionId + "\",\"offerVersion\":1,\"disclosureVersion\":\"" + disclosure + "\",\"customerId\":\"C-1001\"}").statusCode());
        Map<String,Object> updated = service.getLoan(loanId);
        @SuppressWarnings("unchecked")
        Map<String,Object> offer = (Map<String,Object>) updated.get("offer");
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> conditions = (List<Map<String,Object>>) offer.get("conditions");
        assertTrue(conditions.stream().anyMatch(condition -> "agreement".equals(condition.get("id")) && Boolean.TRUE.equals(condition.get("done"))));
        for (Map<String,Object> condition : conditions) {
            if (Boolean.TRUE.equals(condition.get("done"))) continue;
            String conditionId = String.valueOf(condition.get("id"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/loans/" + loanId + "/conditions/" + conditionId))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"done\":true}"))
                    .build();
            assertEquals(204, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals("READY_FOR_FUNDING", service.getLoan(loanId).get("status"), conditions.toString());

        assertEquals(422, postWithHeader(base + "/api/loans/" + loanId + "/funding", "", null).statusCode());
        String key = UUID.randomUUID().toString();
    HttpResponse<String> first = postWithHeader(base + "/api/loans/" + loanId + "/funding", "", key);
        assertEquals(202, first.statusCode(), first.body());
        assertEquals("ACKNOWLEDGED", jsonString(first.body(), "status"));
        assertTrue(first.body().contains("\"disbursed\":false"));
        String providerReference = jsonString(first.body(), "providerReference");

        HttpResponse<String> retry = postWithHeader(base + "/api/loans/" + loanId + "/funding", "", key);
        assertEquals(202, retry.statusCode(), retry.body());
        assertEquals(providerReference, jsonString(retry.body(), "providerReference"));
        assertEquals(409, postWithHeader(base + "/api/loans/" + loanId + "/funding", "", UUID.randomUUID().toString()).statusCode());
        assertEquals("FUNDING_REQUESTED", service.getLoan(loanId).get("status"));
        assertEquals(false, service.getLoan(loanId).get("fundsDisbursed"));
        assertTrue(store.load().orElseThrow().containsKey("fundingRequests"));
        }

    private void writePart(java.io.ByteArrayOutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String multipartBody(String boundary, byte[] pdf, String ownerType, String documentType) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"ownerType\"\r\n\r\n" + ownerType + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"ownerId\"\r\n\r\nbad-id\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"documentType\"\r\n\r\n" + documentType + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fake.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"
                + new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1) + "\r\n--" + boundary + "--\r\n";
    }

    private HttpResponse<String> post(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithHeader(String url, String json, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String verifyBody(String challengeJson) {
        String challengeId = jsonString(challengeJson, "challengeId");
        String code = jsonString(challengeJson, "developmentCode");
        return "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}";
    }

    private String jsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new AssertionError("Missing " + key + " in response: " + json);
        return matcher.group(1);
    }
}
