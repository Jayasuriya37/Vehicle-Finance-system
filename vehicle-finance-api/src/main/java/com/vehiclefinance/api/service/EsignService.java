package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.EsignProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class EsignService {
    public record Session(String sessionId, String agreementId, String status, String signingUrl,
                          String provider, Instant expiresAt) { }
    private record SessionState(String agreementId, String customerId, String status, Instant expiresAt, String signatureHash) { }

    private final EsignProperties properties;
    private final Clock clock;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Autowired
    public EsignService(EsignProperties properties) { this(properties, Clock.systemUTC()); }
    EsignService(EsignProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

    public Session createSession(String agreementId, String customerId) {
        if (!"sandbox".equalsIgnoreCase(properties.getMode())) {
            throw new IllegalStateException("E-sign provider is not configured. Configure an approved provider before starting a signing session.");
        }
        String id = UUID.randomUUID().toString();
        Instant expires = clock.instant().plusSeconds(Math.max(60, properties.getSessionExpirySeconds()));
        sessions.put(id, new SessionState(agreementId, customerId, "SENT", expires, null));
        String base = properties.getSigningBaseUrl() == null || properties.getSigningBaseUrl().isBlank()
                ? "http://localhost:5173/sign" : properties.getSigningBaseUrl().replaceAll("/$", "") + "/sign";
        return new Session(id, agreementId, "SENT", base + "?session=" + id, properties.getProviderName(), expires);
    }

    public SessionStateView status(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) throw new IllegalArgumentException("E-sign session not found.");
        if ("SENT".equals(state.status()) && !clock.instant().isBefore(state.expiresAt())) {
            SessionState expired = new SessionState(state.agreementId(), state.customerId(), "EXPIRED", state.expiresAt(), null);
            sessions.replace(sessionId, state, expired);
            state = expired;
        }
        return new SessionStateView(sessionId, state.agreementId(), state.customerId(), state.status(), state.expiresAt());
    }

    public record SessionStateView(String sessionId, String agreementId, String customerId, String status, Instant expiresAt) { }

    public SessionStateView completeSandboxSession(String sessionId, boolean consent, String disclosureVersion) {
        if (!"sandbox".equalsIgnoreCase(properties.getMode())) throw new IllegalStateException("E-sign provider is not configured.");
        if (!consent || disclosureVersion == null || disclosureVersion.isBlank()) throw new IllegalArgumentException("Explicit consent and disclosure version are required.");
        SessionState state = sessions.get(sessionId);
        if (state == null) throw new IllegalArgumentException("E-sign session not found.");
        if (!clock.instant().isBefore(state.expiresAt())) throw new IllegalStateException("E-sign session has expired.");
        if (!"SENT".equals(state.status())) throw new IllegalStateException("E-sign session is not awaiting signature.");
        SessionState signed = new SessionState(state.agreementId(), state.customerId(), "SIGNED", state.expiresAt(), digest(sessionId + ":" + disclosureVersion + ":" + UUID.randomUUID()));
        sessions.put(sessionId, signed);
        return new SessionStateView(sessionId, signed.agreementId(), signed.customerId(), signed.status(), signed.expiresAt());
    }

    public boolean verifyProviderWebhook(String body, String suppliedSignature) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank() || suppliedSignature == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] supplied = Base64.getDecoder().decode(suppliedSignature);
            return MessageDigest.isEqual(expected, supplied);
        } catch (Exception exception) { return false; }
    }

    private String digest(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Could not create agreement signature reference.", exception); }
    }
}
