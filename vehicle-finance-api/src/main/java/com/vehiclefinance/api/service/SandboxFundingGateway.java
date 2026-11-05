package com.vehiclefinance.api.service;

import java.util.UUID;

/** Local integration adapter. It performs no network request and never moves money. */
public class SandboxFundingGateway implements FundingGateway {
    private final String providerName;

    public SandboxFundingGateway(String providerName) {
        this.providerName = providerName == null || providerName.isBlank() ? "local-funding-sandbox" : providerName;
    }

    @Override
    public FundingReceipt submit(String idempotencyKey, FundingInstruction instruction) {
        String requestId = "SIM-FUND-" + UUID.randomUUID();
        return new FundingReceipt(requestId, providerName, "SIM-ACK-" + UUID.randomUUID(), "ACKNOWLEDGED", false);
    }
}
