package com.ondemandmonitoring.finance.record;

/** Provider query data read only after invoice ownership has been verified. */
public record PaymentReconciliationContext(
        String transactionReference,
        String providerTransactionId,
        String providerRequestDate,
        String paymentUrl) {
}
