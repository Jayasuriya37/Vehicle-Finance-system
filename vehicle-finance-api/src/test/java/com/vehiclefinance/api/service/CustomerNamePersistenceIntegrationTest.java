package com.vehiclefinance.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:vfs-customer-name-test;DB_CLOSE_DELAY=-1")
class CustomerNamePersistenceIntegrationTest {
    @Autowired private StateSnapshotStore store;
    @Autowired private VehicleFinanceService service;
    @Value("${local.server.port}") private int port;

    @Test
    void nameUpdateIsStoredInJdbcAndRestoredByANewService() throws Exception {
        Map<String, Object> before = new LinkedHashMap<>(service.getCustomer("C-1001"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/customers/C-1001/name"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"firstName\":\"Jamie\",\"lastName\":\"Taylor\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Jamie", new ObjectMapper().readTree(response.body()).get("firstName").asText());

        VehicleFinanceService restored = new VehicleFinanceService(store);
        Map<String, Object> customer = restored.getCustomer("C-1001");
        assertEquals("Jamie", customer.get("firstName"));
        assertEquals("Taylor", customer.get("lastName"));
        for (String field : java.util.List.of("email", "mobile", "consent", "kycStatus")) {
            assertEquals(before.get(field), customer.get(field));
        }

        HttpRequest invalid = HttpRequest.newBuilder(request.uri())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"firstName\":\"\",\"lastName\":\"Taylor\"}"))
                .build();
        assertEquals(422, HttpClient.newHttpClient().send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(customer, new VehicleFinanceService(store).getCustomer("C-1001"));
    }
}