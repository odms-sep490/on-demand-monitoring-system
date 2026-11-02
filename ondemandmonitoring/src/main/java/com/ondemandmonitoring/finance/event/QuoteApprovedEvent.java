package com.ondemandmonitoring.finance.event;

import java.time.Instant;

public record QuoteApprovedEvent(
        String customerId,
        String orderId,
        String quoteId,
        Instant approvedAt) {
}
