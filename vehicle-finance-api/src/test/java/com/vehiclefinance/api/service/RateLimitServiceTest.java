package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {
    private MutableClock clock;
    private RateLimitProperties properties;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-28T10:00:00Z"));
        properties = new RateLimitProperties();
        properties.setRequestsPerWindow(2);
        properties.setWindowSeconds(10);
        service = new RateLimitService(properties, clock);
    }

    @Test
    void permitsRequestsUpToConfiguredLimitAndRejectsTheNextRequest() {
        RateLimitService.Decision first = service.tryAcquire("client-a");
        RateLimitService.Decision second = service.tryAcquire("client-a");
        RateLimitService.Decision third = service.tryAcquire("client-a");

        assertTrue(first.allowed());
        assertEquals(1, first.remaining());
        assertTrue(second.allowed());
        assertEquals(0, second.remaining());
        assertFalse(third.allowed());
        assertEquals(2, third.limit());
        assertEquals(0, third.remaining());
        assertEquals(10, third.retryAfterSeconds());
    }

    @Test
    void tracksClientsIndependentlyAndResetsAfterTheWindowExpires() {
        assertTrue(service.tryAcquire("client-a").allowed());
        assertTrue(service.tryAcquire("client-a").allowed());
        assertFalse(service.tryAcquire("client-a").allowed());

        assertTrue(service.tryAcquire("client-b").allowed());
        clock.advanceSeconds(10);
        RateLimitService.Decision afterWindow = service.tryAcquire("client-a");
        assertTrue(afterWindow.allowed());
        assertEquals(1, afterWindow.remaining());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
