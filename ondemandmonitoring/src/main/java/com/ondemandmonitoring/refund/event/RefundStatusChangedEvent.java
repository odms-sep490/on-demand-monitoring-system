package com.ondemandmonitoring.refund.event;

import com.ondemandmonitoring.refund.enums.RefundStatus;
import java.time.Instant;

public record RefundStatusChangedEvent(
        String customerId,
        String orderId,
        String refundId,
        RefundStatus status,
        Instant occurredAt) {
}
