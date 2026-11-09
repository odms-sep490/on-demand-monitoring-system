package com.ondemandmonitoring.finance.gateway;

/** Provider-neutral data needed to reconcile a merchant transaction. */
public record PaymentQueryCommand(
        String transactionReference,
        String providerTransactionId,
        String providerRequestDate,
        String requestIp) {
}
