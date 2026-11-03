package com.ondemandmonitoring.finance.event;

import java.time.Instant;

public record FinalPaymentRequiredEvent(
        String customerId,
        String orderId,
        String invoiceId,
        Instant occurredAt) {
}
