package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.OtpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpVerificationService {
    public enum Channel { EMAIL, SMS }
    public record Challenge(String challengeId, String channel, String destination, long expiresInSeconds,
                            long retryAfterSeconds, String deliveryMode, String developmentCode) { }

    private record Entry(Channel channel, String destination, String codeHash, Instant expiresAt,
                         Instant lastSentAt, int failedAttempts, boolean verified) {
        Entry failed() { return new Entry(channel, destination, codeHash, expiresAt, lastSentAt, failedAttempts + 1, false); }
        Entry verifiedEntry() { return new Entry(channel, destination, codeHash, expiresAt, lastSentAt, failedAttempts, true); }
    }

    private final OtpProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> challenges = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastIssueByDestination = new ConcurrentHashMap<>();
    private final Map<String, Instant> verifiedContacts = new ConcurrentHashMap<>();

    @Autowired
    public OtpVerificationService(OtpProperties properties) { this(properties, Clock.systemUTC()); }

    OtpVerificationService(OtpProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Challenge issue(Channel channel, String rawDestination) {
        if (!"development".equalsIgnoreCase(properties.getDeliveryMode())) {
            throw new IllegalStateException("OTP delivery provider is not configured. Configure an approved email/SMS provider before enabling OTP in this environment.");
        }
        String destination = normalize(channel, rawDestination);
        String contactKey = contactKey(channel, destination);
        Instant now = clock.instant();
        Instant lastSent = lastIssueByDestination.get(contactKey);
        long cooldown = Math.max(1, properties.getResendCooldownSeconds());
        if (lastSent != null && Duration.between(lastSent, now).getSeconds() < cooldown) {
            long retry = cooldown - Math.max(0, Duration.between(lastSent, now).getSeconds());
            throw new OtpRateLimitException(retry);
        }

        String code = generateCode();
        String challengeId = UUID.randomUUID().toString();
        long ttl = Math.max(30, properties.getExpirySeconds());
        Instant expires = now.plusSeconds(ttl);
        challenges.entrySet().removeIf(item -> !now.isBefore(item.getValue().expiresAt()));
        challenges.put(challengeId, new Entry(channel, destination, hash(challengeId, code), expires, now, 0, false));
        lastIssueByDestination.put(contactKey, now);
        return new Challenge(challengeId, channel.name(), mask(channel, destination), ttl, cooldown,
                "development-simulated", properties.isExposeDevelopmentCode() ? code : null);
    }

    public boolean verify(String challengeId, String suppliedCode) {
        Entry entry = challenges.get(challengeId);
        if (entry == null || entry.verified() || !clock.instant().isBefore(entry.expiresAt())) {
            challenges.remove(challengeId);
            return false;
        }
        if (entry.failedAttempts() >= Math.max(1, properties.getMaxAttempts())) {
            challenges.remove(challengeId);
            return false;
        }
        if (suppliedCode == null || !MessageDigest.isEqual(entry.codeHash().getBytes(StandardCharsets.UTF_8), hash(challengeId, suppliedCode.trim()).getBytes(StandardCharsets.UTF_8))) {
            if (entry.failedAttempts() + 1 >= Math.max(1, properties.getMaxAttempts())) challenges.remove(challengeId);
            else challenges.put(challengeId, entry.failed());
            return false;
        }
        challenges.put(challengeId, entry.verifiedEntry());
        verifiedContacts.put(contactKey(entry.channel(), entry.destination()), clock.instant().plusSeconds(900));
        return true;
    }

    public synchronized boolean consumeVerifiedContacts(String email, String mobile) {
        String emailKey = contactKey(Channel.EMAIL, normalize(Channel.EMAIL, email));
        String mobileKey = contactKey(Channel.SMS, normalize(Channel.SMS, mobile));
        Instant now = clock.instant();
        Instant emailExpiry = verifiedContacts.get(emailKey);
        Instant mobileExpiry = verifiedContacts.get(mobileKey);
        if (emailExpiry == null || mobileExpiry == null || !now.isBefore(emailExpiry) || !now.isBefore(mobileExpiry)) {
            if (emailExpiry != null && !now.isBefore(emailExpiry)) verifiedContacts.remove(emailKey);
            if (mobileExpiry != null && !now.isBefore(mobileExpiry)) verifiedContacts.remove(mobileKey);
            return false;
        }
        verifiedContacts.remove(emailKey);
        verifiedContacts.remove(mobileKey);
        return true;
    }

    public synchronized boolean hasVerifiedContacts(String email, String mobile) {
        return isVerified(Channel.EMAIL, email) && isVerified(Channel.SMS, mobile);
    }

    public boolean isVerified(Channel channel, String rawDestination) {
        if (rawDestination == null) return false;
        try {
            String key = contactKey(channel, normalize(channel, rawDestination));
            Instant expiresAt = verifiedContacts.get(key);
            if (expiresAt == null) return false;
            if (!clock.instant().isBefore(expiresAt)) { verifiedContacts.remove(key); return false; }
            return true;
        }
        catch (IllegalArgumentException exception) { return false; }
    }

    private String generateCode() {
        int digits = Math.max(6, Math.min(8, properties.getCodeLength()));
        int bound = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", random.nextInt(bound));
    }

    private String hash(String challengeId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) { throw new IllegalStateException("OTP hashing is unavailable.", exception); }
    }

    private String normalize(Channel channel, String destination) {
        if (destination == null || destination.isBlank()) throw new IllegalArgumentException("Contact destination is required.");
        String normalized = destination.trim();
        if (channel == Channel.EMAIL) {
            normalized = normalized.toLowerCase(java.util.Locale.ROOT);
            if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        } else {
            normalized = normalized.replaceAll("[\\s()-]", "");
            if (!normalized.matches("^\\+?[1-9]\\d{9,14}$")) throw new IllegalArgumentException("Enter a valid mobile number with country code.");
        }
        return normalized;
    }

    private String contactKey(Channel channel, String destination) { return channel.name() + ":" + destination; }
    private String mask(Channel channel, String destination) {
        if (channel == Channel.EMAIL) { int at = destination.indexOf('@'); return destination.substring(0, 1) + "***" + destination.substring(at); }
        return "***" + destination.substring(Math.max(0, destination.length() - 4));
    }

    public static final class OtpRateLimitException extends RuntimeException {
        private final long retryAfterSeconds;
        public OtpRateLimitException(long retryAfterSeconds) { super("Wait before requesting another verification code."); this.retryAfterSeconds=retryAfterSeconds; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
