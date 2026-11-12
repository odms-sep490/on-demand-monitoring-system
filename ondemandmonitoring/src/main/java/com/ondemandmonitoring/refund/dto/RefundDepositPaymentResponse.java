package com.ondemandmonitoring.refund.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundDepositPaymentResponse(
        String paymentId,
        BigDecimal amount,
        String currency,
        String provider,
        String transactionReference,
        Instant paidAt) {
}
