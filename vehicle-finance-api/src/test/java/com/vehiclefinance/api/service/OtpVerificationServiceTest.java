package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.OtpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpVerificationServiceTest {
    private OtpProperties properties;
    private MutableClock clock;
    private OtpVerificationService service;

    @BeforeEach
    void setUp() {
        properties = new OtpProperties();
        properties.setDeliveryMode("development");
        properties.setExposeDevelopmentCode(true);
        properties.setExpirySeconds(60);
        properties.setResendCooldownSeconds(15);
        properties.setMaxAttempts(3);
        clock = new MutableClock(Instant.parse("2026-10-28T10:00:00Z"));
        service = new OtpVerificationService(properties, clock);
    }

    @Test
    void issuesCodeVerifiesExactDestinationAndConsumesProofOnce() {
        var email = service.issue(OtpVerificationService.Channel.EMAIL, "Applicant@Example.test");
        var sms = service.issue(OtpVerificationService.Channel.SMS, "+14155550123");

        assertTrue(service.verify(email.challengeId(), email.developmentCode()));
        assertTrue(service.verify(sms.challengeId(), sms.developmentCode()));
        assertTrue(service.hasVerifiedContacts("applicant@example.test", "+14155550123"));
        assertTrue(service.consumeVerifiedContacts("applicant@example.test", "+14155550123"));
        assertFalse(service.hasVerifiedContacts("applicant@example.test", "+14155550123"));
        assertFalse(service.verify(email.challengeId(), email.developmentCode()));
    }

    @Test
    void rejectsWrongCodesAndExpiresAfterAttemptLimit() {
        var challenge = service.issue(OtpVerificationService.Channel.EMAIL, "user@example.test");
        assertFalse(service.verify(challenge.challengeId(), "000000"));
        assertFalse(service.verify(challenge.challengeId(), "000000"));
        assertFalse(service.verify(challenge.challengeId(), "000000"));
        assertFalse(service.verify(challenge.challengeId(), challenge.developmentCode()));
    }

    @Test
    void enforcesExpiryAndResendCooldown() {
        var challenge = service.issue(OtpVerificationService.Channel.SMS, "+14155550199");
        assertThrows(OtpVerificationService.OtpRateLimitException.class,
                () -> service.issue(OtpVerificationService.Channel.SMS, "+14155550199"));
        clock.advanceSeconds(61);
        assertFalse(service.verify(challenge.challengeId(), challenge.developmentCode()));
        assertTrue(service.issue(OtpVerificationService.Channel.SMS, "+14155550199").developmentCode() != null);
    }

    @Test
    void disablesDevelopmentOtpWhenProviderModeIsNotConfigured() {
        properties.setDeliveryMode("disabled");
        assertThrows(IllegalStateException.class,
                () -> service.issue(OtpVerificationService.Channel.EMAIL, "user@example.test"));
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
