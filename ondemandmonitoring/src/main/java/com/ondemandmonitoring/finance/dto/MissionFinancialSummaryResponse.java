package com.ondemandmonitoring.finance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.finance.enums.PaymentProvider;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MissionFinancialSummaryResponse(
        String missionId,
        MissionPurpose purpose,
        String currency,
        BigDecimal missionGrossChecklistAmount,
        InvoiceSummary invoice,
        List<PaymentHistoryEntry> depositPayments,
        MissionPaymentSummary missionPayment,
        PriceBreakdown priceBreakdown) {

    public record PriceBreakdown(
            BigDecimal checklistAmount,
            BigDecimal allocatedAdjustment,
            BigDecimal missionTotalAmount,
            BigDecimal allocatedDeposit,
            BigDecimal milestoneAmount) {}

    public record InvoiceSummary(
            String invoiceId,
            BigDecimal totalAmount,
            BigDecimal depositAmount,
            BigDecimal paidAmount,
            BigDecimal remainingAmount,
            DepositStatus depositStatus,
            Instant issuedAt,
            Instant depositExpiresAt) {}

    public record MissionPaymentSummary(
            String milestoneId,
            BigDecimal amount,
            MissionPaymentMilestoneStatus status,
            Instant handedOverAt,
            Instant paymentExpiresAt,
            List<PaymentHistoryEntry> payments) {}

    public record PaymentHistoryEntry(
            String id,
            PaymentType type,
            BigDecimal amount,
            PaymentStatus status,
            PaymentProvider provider,
            String transactionReferenceMasked,
            Instant paidAt) {}
}
