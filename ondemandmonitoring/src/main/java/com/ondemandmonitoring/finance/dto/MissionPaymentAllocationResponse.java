package com.ondemandmonitoring.finance.dto;

import java.math.BigDecimal;
import java.util.List;

public record MissionPaymentAllocationResponse(
        String invoiceId,
        BigDecimal allocationTargetAmount,
        BigDecimal allocatedAmount,
        boolean allocationComplete,
        List<MissionPaymentMilestoneResponse> milestones) {}
