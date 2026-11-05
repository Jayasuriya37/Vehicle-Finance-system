package com.vehiclefinance.api.config;

import com.vehiclefinance.api.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {
    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setRequestsPerWindow(1);
        properties.setWindowSeconds(30);
        filter = new RateLimitFilter(new RateLimitService(properties, Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"))));
    }

    @Test
    void addsLimitHeadersAndReturnsProblemResponseWhenLimitIsExceeded() throws Exception {
        MockHttpServletResponse firstResponse = invoke("GET", "/api/bootstrap", "192.0.2.10", "trace-1");
        MockHttpServletResponse blockedResponse = invoke("GET", "/api/bootstrap", "192.0.2.10", "trace-2");

        assertEquals(200, firstResponse.getStatus());
        assertEquals("1", firstResponse.getHeader("X-RateLimit-Limit"));
        assertEquals("0", firstResponse.getHeader("X-RateLimit-Remaining"));
        assertEquals(429, blockedResponse.getStatus());
        assertEquals("30", blockedResponse.getHeader("Retry-After"));
        assertTrue(blockedResponse.getContentType().startsWith("application/problem+json"));
        assertTrue(blockedResponse.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        assertTrue(blockedResponse.getContentAsString().contains("trace-2"));
    }

    @Test
    void doesNotRateLimitPreflightOrNonApiRoutes() throws Exception {
        MockHttpServletResponse preflightFirst = invoke("OPTIONS", "/api/bootstrap", "192.0.2.11", null);
        MockHttpServletResponse preflightSecond = invoke("OPTIONS", "/api/bootstrap", "192.0.2.11", null);
        MockHttpServletResponse healthFirst = invoke("GET", "/health", "192.0.2.12", null);
        MockHttpServletResponse healthSecond = invoke("GET", "/health", "192.0.2.12", null);

        assertEquals(200, preflightFirst.getStatus());
        assertEquals(200, preflightSecond.getStatus());
        assertEquals(200, healthFirst.getStatus());
        assertEquals(200, healthSecond.getStatus());
        assertFalse(preflightSecond.containsHeader("Retry-After"));
        assertFalse(healthSecond.containsHeader("Retry-After"));
    }

    @Test
    void canBeDisabledThroughConfiguration() throws Exception {
        properties.setEnabled(false);
        MockHttpServletResponse first = invoke("GET", "/api/bootstrap", "192.0.2.13", null);
        MockHttpServletResponse second = invoke("GET", "/api/bootstrap", "192.0.2.13", null);

        assertEquals(200, first.getStatus());
        assertEquals(200, second.getStatus());
        assertFalse(second.containsHeader("Retry-After"));
    }

    private MockHttpServletResponse invoke(String method, String path, String remoteAddress, String correlationId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        if (correlationId != null) request.addHeader("X-Correlation-ID", correlationId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (!"OPTIONS".equals(method) && !path.equals("/health") && response.getStatus() == 200) {
            assertTrue(chain.getRequest() != null, "allowed API request should continue to the next filter");
        }
        return response;
    }
}
