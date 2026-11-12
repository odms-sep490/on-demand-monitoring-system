package com.ondemandmonitoring.refund.dto;

import com.ondemandmonitoring.refund.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RefundResponse(
        String id,
        String orderId,
        String orderCode,
        String orderTitle,
        String customerId,
        String customerName,
        String customerEmail,
        String invoiceId,
        String invoiceNumber,
        List<RefundDepositPaymentResponse> depositPayments,
        String reason,
        RefundStatus status,
        String requestedById,
        String requestedByName,
        Instant requestedAt,
        String processingAdminId,
        String processingAdminName,
        Instant processingAt,
        String completedById,
        String completedByName,
        Instant completedAt,
        String rejectedById,
        String rejectedByName,
        Instant rejectedAt,
        String rejectionReason,
        String cancelledById,
        String cancelledByName,
        Instant cancelledAt,
        String cancellationReason,
        BigDecimal refundableAmount,
        BigDecimal refundedAmount,
        List<RefundTransferAttemptResponse> attempts) {
}
