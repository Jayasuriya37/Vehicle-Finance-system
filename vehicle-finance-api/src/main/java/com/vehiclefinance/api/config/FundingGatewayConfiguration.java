package com.vehiclefinance.api.config;

import com.vehiclefinance.api.service.DisabledFundingGateway;
import com.vehiclefinance.api.service.FundingGateway;
import com.vehiclefinance.api.service.SandboxFundingGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FundingGatewayConfiguration {
    @Bean
    FundingGateway fundingGateway(FundingProperties properties) {
        if ("sandbox".equalsIgnoreCase(properties.getMode())) {
            return new SandboxFundingGateway(properties.getProviderName());
        }
        return new DisabledFundingGateway();
    }
}
