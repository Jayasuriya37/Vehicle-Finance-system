package com.vehiclefinance.api.service;

/** Provider boundary for an accepted funding hand-off. Implementations must not claim settlement on acknowledgement. */
public interface FundingGateway {
    record FundingInstruction(String loanId, String offerId, long amountMinorUnits) { }
    record FundingReceipt(String requestId, String provider, String providerReference,
                          String status, boolean disbursed) { }

    FundingReceipt submit(String idempotencyKey, FundingInstruction instruction);
}
