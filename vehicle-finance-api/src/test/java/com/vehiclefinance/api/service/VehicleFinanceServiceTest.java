package com.vehiclefinance.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleFinanceServiceTest {
    private VehicleFinanceService service;

    @BeforeEach
    void setUp() {
        service = new VehicleFinanceService();
    }

    @Test
    void updatesCustomerNameWithoutChangingContactsConsentOrKyc() {
        Map<String, Object> before = new java.util.LinkedHashMap<>(service.getCustomer("C-1001"));
        Map<String, Object> draft = service.createLoan(validLoan("VEH-1001", "PV-26-041"));
        Map<String, Object> updated = service.updateCustomerName("C-1001", Map.of("firstName", " Jamie ", "lastName", " O'Connor "));

        assertEquals("Jamie", updated.get("firstName"));
        assertEquals("O'Connor", updated.get("lastName"));
        for (String field : java.util.List.of("email", "mobile", "consent", "kycStatus")) {
            assertEquals(before.get(field), updated.get(field));
        }
        assertEquals("Jamie O'Connor", service.getLoan(String.valueOf(draft.get("applicationId"))).get("applicantName"));
    }

    @Test
    void rejectsInvalidCustomerNamesWithoutPartialUpdates() {
        Map<String, Object> before = new java.util.LinkedHashMap<>(service.getCustomer("C-1001"));
        for (Map<String, Object> payload : java.util.List.<Map<String, Object>>of(
                Map.of("firstName", "Jamie", "lastName", " "),
                Map.of("firstName", "", "lastName", "Morgan"),
                Map.of("firstName", "Jamie"),
                Map.of("firstName", "A".repeat(101), "lastName", "Morgan"),
                Map.of("firstName", 123, "lastName", "Morgan"),
                Map.of("firstName", "Jamie", "lastName", "Morgan", "email", "other@example.test"))) {
            assertThrows(IllegalArgumentException.class, () -> service.updateCustomerName("C-1001", payload));
            assertEquals(before, service.getCustomer("C-1001"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.updateCustomerName("unknown", Map.of("firstName", "Jamie", "lastName", "Morgan")));
    }

    @Test
    void createsAndSubmitsDraftOnlyForCurrentEligibleVehicle() {
        Map<String, Object> created = service.createLoan(validLoan("VEH-1001", "PV-26-041"));
        String id = (String) created.get("applicationId");

        assertEquals("DRAFT", service.getLoan(id).get("status"));
        service.submitLoan(id);
        assertEquals("CREDIT_REVIEW", service.getLoan(id).get("status"));
        int timelineSize = ((java.util.List<?>) service.getLoan(id).get("timeline")).size();
        service.submitLoan(id);
        assertEquals(timelineSize, ((java.util.List<?>) service.getLoan(id).get("timeline")).size());
    }

    @Test
    void normalizesCustomerDetailsAndPreservesAllOtherCustomerFields() {
        Map<String, Object> before = new java.util.LinkedHashMap<>(service.getCustomer("C-1001"));
        Map<String, Object> draft = service.createLoan(validLoan("VEH-1001", "PV-26-041"));
        Map<String, Object> payload = Map.of("firstName", " Jamie ", "lastName", " Taylor ",
                "email", " JAMIE@Example.Test ", "mobile", " +1 (415) 555-0123 ");
        Map<String, Object> normalized = service.validateCustomerDetails(payload);
        assertEquals(before, service.getCustomer("C-1001"), "Validation must not mutate the customer");
        assertEquals(Map.of("firstName", "Jamie", "lastName", "Taylor", "email", "jamie@example.test",
                "mobile", "+14155550123"), normalized);

        Map<String, Object> updated = service.updateCustomerDetails("C-1001", payload);
        Map<String, Object> expected = new java.util.LinkedHashMap<>(before);
        expected.putAll(normalized);
        assertEquals(expected, updated);
        assertEquals("Jamie Taylor", service.getLoan(String.valueOf(draft.get("applicationId"))).get("applicantName"));
    }

    @Test
    void rejectsInvalidCustomerDetailsBeforeAnyMutation() {
        Map<String, Object> valid = Map.of("firstName", "Jamie", "lastName", "Taylor",
                "email", "jamie@example.test", "mobile", "+14155550123");
        Map<String, Object> before = new java.util.LinkedHashMap<>(service.getCustomer("C-1001"));
        Map<String, Object> draft = service.createLoan(validLoan("VEH-1001", "PV-26-041"));
        String draftId = String.valueOf(draft.get("applicationId"));
        Object applicantName = service.getLoan(draftId).get("applicantName");
        int auditSize = ((java.util.List<?>) service.auditResponse().get("items")).size();
        java.util.List<Map<String, Object>> invalid = new java.util.ArrayList<>();
        invalid.add(null);
        invalid.add(Map.of());
        for (String field : valid.keySet()) {
            Map<String, Object> missing = new java.util.LinkedHashMap<>(valid);
            missing.remove(field);
            invalid.add(missing);
            for (Object value : java.util.Arrays.asList(null, 123, true, java.util.List.of("text"), "", " ")) {
                Map<String, Object> badTypeOrBlank = new java.util.LinkedHashMap<>(valid);
                badTypeOrBlank.put(field, value);
                invalid.add(badTypeOrBlank);
            }
        }
        for (String field : java.util.List.of("firstName", "lastName")) {
            Map<String, Object> tooLong = new java.util.LinkedHashMap<>(valid);
            tooLong.put(field, "A".repeat(101));
            invalid.add(tooLong);
        }
        for (String email : java.util.List.of("missing-at.test", "a@@example.test", "a b@example.test", "a@example", "a".repeat(242) + "@example.test")) {
            Map<String, Object> badEmail = new java.util.LinkedHashMap<>(valid);
            badEmail.put("email", email);
            invalid.add(badEmail);
        }
        for (String mobile : java.util.List.of("123456789", "1234567890123456", "0123456789", "+0123456789", "++14155550123", "1415abc0123", "1415.555.0123")) {
            Map<String, Object> badMobile = new java.util.LinkedHashMap<>(valid);
            badMobile.put("mobile", mobile);
            invalid.add(badMobile);
        }
        for (String field : java.util.List.of("id", "consent", "consentVersion", "kycStatus", "role")) {
            Map<String, Object> sensitive = new java.util.LinkedHashMap<>(valid);
            sensitive.put(field, "forbidden");
            invalid.add(sensitive);
        }
        for (Map<String, Object> payload : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.validateCustomerDetails(payload));
            assertThrows(IllegalArgumentException.class, () -> service.updateCustomerDetails("C-1001", payload));
            assertEquals(before, service.getCustomer("C-1001"));
            assertEquals(applicantName, service.getLoan(draftId).get("applicantName"));
            assertEquals(auditSize, ((java.util.List<?>) service.auditResponse().get("items")).size());
        }
        assertThrows(IllegalArgumentException.class, () -> service.updateCustomerDetails("unknown", valid));
    }

    @Test
    void acceptsCustomerDetailsAtLengthBoundaries() {
        for (String mobile : java.util.List.of("1234567890", "+123456789012345")) {
            Map<String, Object> updated = service.updateCustomerDetails("C-1001", Map.of(
                    "firstName", "A".repeat(100), "lastName", "B".repeat(100),
                    "email", "a".repeat(241) + "@example.test", "mobile", mobile));
            assertEquals(254, ((String) updated.get("email")).length());
            assertEquals(mobile, updated.get("mobile"));
        }
    }

    @Test
    void rejectsStalePriceAndInactiveDealerForOriginations() {
        assertThrows(IllegalStateException.class, () -> service.createLoan(validLoan("VEH-1001", "old-price")));
        assertThrows(IllegalArgumentException.class, () -> service.createLoan(validLoan("VEH-1006", "PV-26-018")));
    }

    @Test
    void acceptsOnlyCurrentUnexpiredOfferAndUpdatesWorkflow() {
        service.acceptOffer("OFF-10036", 1);
        Map<String, Object> loan = service.getLoan("VF-2026-00136");
        assertEquals("CONDITIONS_PENDING", loan.get("status"));
        assertTrue(((Map<?, ?>) loan.get("offer")).containsKey("acceptedAt"));

        service.acceptOffer("OFF-10036", 1);
        assertEquals("CONDITIONS_PENDING", service.getLoan("VF-2026-00136").get("status"));
        assertThrows(IllegalStateException.class, () -> service.acceptOffer("OFF-10036", 2));
    }

    @Test
    void createsKycCaseAndRequiresReasonForRework() {
        Map<String, Object> created = service.createKycCase(Map.of("customerId", "C-1001"));
        String id = (String) created.get("kycCaseId");

        assertEquals("PENDING", service.getKycCase(id).get("status"));
        assertThrows(IllegalStateException.class, () -> service.decideKycCase(id, Map.of("outcome", "VERIFIED")));
        assertThrows(IllegalArgumentException.class, () -> service.decideKycCase(id, Map.of("outcome", "REWORK")));
        service.decideKycCase(id, Map.of("outcome", "REWORK", "reason", "Address proof is unclear"));
        assertEquals("REWORK", service.getKycCase(id).get("status"));
    }

    @Test
    void appliesVehicleFiltersAndLoanPagination() {
        Map<String, Object> vehicles = service.searchVehicles(Map.of("make", "Honda", "available", true));
        assertEquals(1, vehicles.get("total"));
        Map<String, Object> loans = service.loansResponse("CREDIT_REVIEW", "C-1001", 0, 1);
        assertEquals(1, loans.get("items") instanceof java.util.List<?> items ? items.size() : 0);
        assertFalse(((java.util.List<?>) loans.get("items")).isEmpty());
    }

    @Test
    void createsCatalogueVehicleOnlyForActiveDealerAndAssignsPriceVersion() {
        Map<String, Object> payload = Map.ofEntries(
            Map.entry("make", "Demo"), Map.entry("model", "City EV"), Map.entry("variant", "Base"),
            Map.entry("year", 2026), Map.entry("body", "Sedan"), Map.entry("fuel", "Electric"),
            Map.entry("transmission", "Automatic"), Map.entry("price", 1_250_000), Map.entry("dealerId", "D-1042"),
            Map.entry("available", true), Map.entry("eligible", false), Map.entry("stock", "In stock")
        );

        Map<String, Object> created = service.createVehicle(payload);
        assertTrue(String.valueOf(created.get("id")).startsWith("VEH-"));
        assertTrue(String.valueOf(created.get("priceVersion")).startsWith("PV-"));
        assertEquals(false, created.get("eligible"));

        Map<String, Object> inactiveDealerPayload = new java.util.HashMap<>(payload);
        inactiveDealerPayload.put("dealerId", "D-1046");
        assertThrows(IllegalArgumentException.class, () -> service.createVehicle(inactiveDealerPayload));
    }

    @Test
    void createsDealerBranchesAndStaffRecords() {
        Map<String, Object> branch = service.createDealerBranch("D-1042", Map.of("name", "West Branch", "address", "West Road", "city", "Chennai"));
        Map<String, Object> staff = service.createDealerStaff("D-1042", Map.of("name", "Sample User", "email", "sample.user@example.test", "role", "DEALER_USER"));

        assertEquals("D-1042", branch.get("dealerId"));
        assertEquals("PENDING", branch.get("status"));
        assertEquals("D-1042", staff.get("dealerId"));
        assertEquals("PENDING_INVITATION", staff.get("status"));
        assertEquals(5, service.dealerBranchesResponse("D-1042").get("total"));
        assertEquals(3, service.dealerStaffResponse("D-1042").get("total"));
    }

    private Map<String, Object> validLoan(String vehicleId, String priceVersion) {
        return Map.of(
                "vehicleId", vehicleId,
                "priceVersion", priceVersion,
                "downPayment", 350000,
                "tenureMonths", 48,
                "income", 90000,
                "obligations", 12000,
                "employment", "Salaried",
                "source", "Direct"
        );
    }
}
