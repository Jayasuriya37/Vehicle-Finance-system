package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.EsignProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsignServiceTest {
    @Test
    void sandboxSessionRequiresExplicitConsentAndExpires() {
        EsignProperties properties=new EsignProperties();properties.setMode("sandbox");properties.setSessionExpirySeconds(60);
        MutableClock clock=new MutableClock(Instant.parse("2026-10-29T10:00:00Z"));
        EsignService service=new EsignService(properties,clock);
        EsignService.Session created=service.createSession("OFF-100", "C-100");
        assertEquals("SENT",created.status());
        assertTrue(created.signingUrl().contains(created.sessionId()));
        assertThrows(IllegalArgumentException.class,()->service.completeSandboxSession(created.sessionId(),false,"DISCLOSURE-1"));
        assertEquals("SIGNED",service.completeSandboxSession(created.sessionId(),true,"DISCLOSURE-1").status());

        EsignService.Session expiring=service.createSession("OFF-101","C-100");
        clock.advanceSeconds(61);
        assertEquals("EXPIRED",service.status(expiring.sessionId()).status());
    }

    @Test
    void disabledProviderFailsClosed() {
        EsignService service=new EsignService(new EsignProperties());
        assertThrows(IllegalStateException.class,()->service.createSession("OFF-100","C-100"));
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current){this.current=current;}
        void advanceSeconds(long seconds){current=current.plusSeconds(seconds);}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return current;}
    }
}
