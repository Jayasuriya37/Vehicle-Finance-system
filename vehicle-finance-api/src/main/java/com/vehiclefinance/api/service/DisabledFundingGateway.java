package com.vehiclefinance.api.service;

public class DisabledFundingGateway implements FundingGateway {
    @Override
    public FundingReceipt submit(String idempotencyKey, FundingInstruction instruction) {
        throw new IllegalStateException("Funding provider is disabled. Configure an approved provider and production authorization before submitting funding.");
    }
}
