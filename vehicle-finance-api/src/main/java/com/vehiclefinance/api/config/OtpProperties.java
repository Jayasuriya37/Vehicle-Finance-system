package com.vehiclefinance.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vfs.otp")
public class OtpProperties {
    private String deliveryMode = "development";
    private boolean exposeDevelopmentCode = true;
    private int codeLength = 6;
    private long expirySeconds = 300;
    private long resendCooldownSeconds = 30;
    private int maxAttempts = 5;

    public String getDeliveryMode() { return deliveryMode; }
    public void setDeliveryMode(String deliveryMode) { this.deliveryMode = deliveryMode; }
    public boolean isExposeDevelopmentCode() { return exposeDevelopmentCode; }
    public void setExposeDevelopmentCode(boolean exposeDevelopmentCode) { this.exposeDevelopmentCode = exposeDevelopmentCode; }
    public int getCodeLength() { return codeLength; }
    public void setCodeLength(int codeLength) { this.codeLength = codeLength; }
    public long getExpirySeconds() { return expirySeconds; }
    public void setExpirySeconds(long expirySeconds) { this.expirySeconds = expirySeconds; }
    public long getResendCooldownSeconds() { return resendCooldownSeconds; }
    public void setResendCooldownSeconds(long resendCooldownSeconds) { this.resendCooldownSeconds = resendCooldownSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
