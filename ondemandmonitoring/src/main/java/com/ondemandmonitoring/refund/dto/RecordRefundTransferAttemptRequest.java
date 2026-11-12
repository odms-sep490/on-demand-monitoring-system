package com.ondemandmonitoring.refund.dto;

import com.ondemandmonitoring.refund.enums.TransferOutcome;
import com.ondemandmonitoring.refund.enums.RefundExecutionChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record RecordRefundTransferAttemptRequest(
        @NotNull RefundExecutionChannel executionChannel,
        @Size(max = 120) String beneficiaryBank,
        @Size(max = 120) String beneficiaryName,
        @Size(max = 80) String beneficiaryAccountNumber,
        boolean beneficiaryConfirmedWithCustomer,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 120) String transactionReference,
        @NotNull Instant transferredAt,
        @Size(max = 1000) String note,
        @NotNull TransferOutcome outcome,
        @Size(max = 1000) String failureReason) {
}
