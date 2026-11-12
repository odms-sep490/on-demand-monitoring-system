package com.ondemandmonitoring.refund.dto;

import java.math.BigDecimal;

/** Transaction totals from OMSS records; these values do not represent bank cash. */
public record RefundAdminSummaryResponse(
        BigDecimal successfulPaymentsTotal,
        BigDecimal completedRefundsTotal,
        BigDecimal pendingRefundsTotal,
        long pendingRefundCount,
        BigDecimal outstandingMissionMilestonesTotal,
        long outstandingMissionMilestoneCount) {
}
