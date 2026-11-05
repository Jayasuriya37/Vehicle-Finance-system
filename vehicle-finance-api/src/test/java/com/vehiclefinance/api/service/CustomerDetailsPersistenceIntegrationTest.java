package com.vehiclefinance.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:vfs-customer-details-test;DB_CLOSE_DELAY=-1")
class CustomerDetailsPersistenceIntegrationTest {
    @Autowired private StateSnapshotStore store;
    @Autowired private VehicleFinanceService service;
    @Autowired private OtpVerificationService otp;
    @Value("${local.server.port}") private int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void persistBaseline() {
        service.updateCustomerDetails("C-1001", newDetails());
        store.save(service.exportSnapshot());
    }

    @Test
    void contactChangesRequireBothProofsAndPersistWithoutResettingOtherFields() throws Exception {
        Map<String, Object> before = customer();
        Map<String, Object> details = newDetails();
        assertBlockedWithoutMutation(details, before);

        verify("EMAIL", (String) details.get("email"));
        assertBlockedWithoutMutation(details, before);
        assertTrue(otp.isVerified(OtpVerificationService.Channel.EMAIL, (String) details.get("email")));
        verify("SMS", (String) details.get("mobile"));

        Map<String, Object> expected = new LinkedHashMap<>(before);
        expected.putAll(details);
        assertEquals(200, patch(details).statusCode());
        assertEquals(expected, customer());
        assertEquals(expected, new VehicleFinanceService(store).getCustomer("C-1001"));
        assertFalse(otp.hasVerifiedContacts((String) details.get("email"), (String) details.get("mobile")));

        // An unchanged-contact name edit does not need proofs, even after consumption.
        details.put("firstName", "Renamed");
        assertEquals(200, patch(details).statusCode());

        // Move away with fresh proofs, then try returning to the previously verified contacts.
        Map<String, Object> other = newDetails();
        verifyBoth(other);
        assertEquals(200, patch(other).statusCode());
        assertBlockedWithoutMutation(details, customer());
    }

    @Test
    void smsProofAloneCannotChangeContacts() throws Exception {
        Map<String, Object> before = customer();
        Map<String, Object> details = newDetails();
        verify("SMS", (String) details.get("mobile"));
        assertBlockedWithoutMutation(details, before);
        assertTrue(otp.isVerified(OtpVerificationService.Channel.SMS, (String) details.get("mobile")));
        verify("EMAIL", (String) details.get("email"));
        assertEquals(200, patch(details).statusCode());
    }

    @Test
    void normalizedUnchangedContactsNeedNoProofAndDoNotConsumeExistingProofs() throws Exception {
        Map<String, Object> before = customer();
        Map<String, Object> details = editable(before);
        verifyBoth(details);
        details.put("firstName", " Jamie ");
        details.put("email", " " + ((String) before.get("email")).toUpperCase(java.util.Locale.ROOT) + " ");
        details.put("mobile", " (" + before.get("mobile") + ") ");
        HttpResponse<String> response = patch(details);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Jamie", json.readTree(response.body()).get("firstName").asText());
        assertEquals(customer(), new VehicleFinanceService(store).getCustomer("C-1001"));
        assertTrue(otp.hasVerifiedContacts((String) before.get("email"), (String) before.get("mobile")));
        assertEquals(before.get("kycStatus"), customer().get("kycStatus"));
        assertEquals(before.get("consent"), customer().get("consent"));
    }

    @Test
    void changingEmailAloneStillRequiresBothDestinationProofs() throws Exception {
        assertSingleContactChangeRequiresBothProofs("email");
    }

    @Test
    void changingMobileAloneStillRequiresBothDestinationProofs() throws Exception {
        assertSingleContactChangeRequiresBothProofs("mobile");
    }

    private void assertSingleContactChangeRequiresBothProofs(String changedField) throws Exception {
        Map<String, Object> before = customer();
        Map<String, Object> details = editable(before);
        details.put(changedField, newDetails().get(changedField));
        verify("email".equals(changedField) ? "EMAIL" : "SMS", (String) details.get(changedField));
        assertBlockedWithoutMutation(details, before);
        verify("email".equals(changedField) ? "SMS" : "EMAIL",
                (String) details.get("email".equals(changedField) ? "mobile" : "email"));
        assertEquals(200, patch(details).statusCode());
    }

    @Test
    void invalidPayloadsAndUnknownCustomersDoNotConsumeProofsOrMutateState() throws Exception {
        Map<String, Object> before = customer();
        Map<String, Object> details = newDetails();
        verifyBoth(details);
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
                "lastName", " ", "email", "invalid", "mobile", 123,
                "consent", false, "consentVersion", "replacement", "kycStatus", "VERIFIED", "id", "other").entrySet()) {
            Map<String, Object> payload = new LinkedHashMap<>(details);
            payload.put(invalid.getKey(), invalid.getValue());
            assertEquals(422, patch(payload).statusCode());
            assertEquals(before, customer());
            assertEquals(before, new VehicleFinanceService(store).getCustomer("C-1001"));
            assertTrue(otp.hasVerifiedContacts((String) details.get("email"), (String) details.get("mobile")));
        }
        assertEquals(422, request("PATCH", "/api/customers/unknown/details", details).statusCode());
        assertTrue(otp.hasVerifiedContacts((String) details.get("email"), (String) details.get("mobile")));

        // Proofs issued for normalized destinations also work with formatted input.
        Map<String, Object> formatted = new LinkedHashMap<>(details);
        formatted.put("email", " " + ((String) details.get("email")).toUpperCase(java.util.Locale.ROOT) + " ");
        formatted.put("mobile", " (" + details.get("mobile") + ") ");
        HttpResponse<String> response = patch(formatted);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(details.get("email"), customer().get("email"));
        assertEquals(details.get("mobile"), customer().get("mobile"));
    }

    private Map<String, Object> customer() {
        return new LinkedHashMap<>(service.getCustomer("C-1001"));
    }

    private Map<String, Object> editable(Map<String, Object> customer) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : java.util.List.of("firstName", "lastName", "email", "mobile")) {
            result.put(field, customer.get(field));
        }
        return result;
    }

    private Map<String, Object> newDetails() {
        return new LinkedHashMap<>(Map.of("firstName", "Jamie", "lastName", "Taylor",
                "email", "details-" + UUID.randomUUID() + "@example.test",
                "mobile", "+1415" + String.format("%07d", Math.floorMod(UUID.randomUUID().hashCode(), 10_000_000))));
    }

    private void assertBlockedWithoutMutation(Map<String, Object> details, Map<String, Object> before) throws Exception {
        Map<String, Object> snapshot = store.load().orElseThrow();
        HttpResponse<String> response = patch(details);
        assertEquals(409, response.statusCode(), response.body());
        assertEquals(before, customer());
        assertEquals(snapshot, store.load().orElseThrow());
        assertEquals(before, new VehicleFinanceService(store).getCustomer("C-1001"));
    }

    private void verifyBoth(Map<String, Object> details) throws Exception {
        verify("EMAIL", (String) details.get("email"));
        verify("SMS", (String) details.get("mobile"));
    }

    private void verify(String channel, String destination) throws Exception {
        HttpResponse<String> issued = request("POST", "/api/otp/challenges", Map.of("channel", channel, "destination", destination));
        assertEquals(202, issued.statusCode(), issued.body());
        JsonNode challenge = json.readTree(issued.body());
        HttpResponse<String> verified = request("POST", "/api/otp/challenges/verify", Map.of(
                "challengeId", challenge.get("challengeId").asText(), "code", challenge.get("developmentCode").asText()));
        assertEquals(200, verified.statusCode(), verified.body());
    }

    private HttpResponse<String> patch(Map<String, Object> details) throws Exception {
        return request("PATCH", "/api/customers/C-1001/details", details);
    }

    private HttpResponse<String> request(String method, String path, Map<String, Object> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}