package com.ondemandmonitoring.finance.websocket;

import java.time.Instant;

public record CustomerOrderEvent(
        String eventType,
        String orderId,
        String quoteId,
        String invoiceId,
        Instant occurredAt) {
}
