package com.vehiclefinance.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vfs.esign")
public class EsignProperties {
    private String mode = "disabled";
    private String providerName = "not-configured";
    private long sessionExpirySeconds = 900;
    private String signingBaseUrl = "";
    private String webhookSecret = "";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public long getSessionExpirySeconds() { return sessionExpirySeconds; }
    public void setSessionExpirySeconds(long sessionExpirySeconds) { this.sessionExpirySeconds = sessionExpirySeconds; }
    public String getSigningBaseUrl() { return signingBaseUrl; }
    public void setSigningBaseUrl(String signingBaseUrl) { this.signingBaseUrl = signingBaseUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
