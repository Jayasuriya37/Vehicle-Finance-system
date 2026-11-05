package com.vehiclefinance.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vfs.funding")
public class FundingProperties {
    private String mode = "disabled";
    private String providerName = "not-configured";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
}
