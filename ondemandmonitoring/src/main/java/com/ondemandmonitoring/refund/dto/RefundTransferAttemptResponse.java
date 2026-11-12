package com.ondemandmonitoring.refund.dto;

import com.ondemandmonitoring.refund.enums.TransferOutcome;
import com.ondemandmonitoring.refund.enums.RefundExecutionChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RefundTransferAttemptResponse(
        String id,
        int attemptNumber,
        RefundExecutionChannel executionChannel,
        String beneficiaryBank,
        String beneficiaryName,
        String beneficiaryAccountNumber,
        boolean beneficiaryConfirmedWithCustomer,
        BigDecimal amount,
        String transactionReference,
        Instant transferredAt,
        String note,
        TransferOutcome outcome,
        String failureReason,
        String recordedById,
        String recordedByName,
        Instant createdAt,
        List<RefundReceiptResponse> receipts) {
}
