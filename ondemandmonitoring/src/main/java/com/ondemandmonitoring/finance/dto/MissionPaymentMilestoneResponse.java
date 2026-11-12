package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MissionPaymentMilestoneResponse(
        String id,
        String missionId,
        String missionCode,
        String scopeLabel,
        BigDecimal amount,
        String currency,
        MissionPaymentMilestoneStatus status,
        Instant handedOverAt,
        Instant paymentExpiresAt,
        List<PaymentResponse> payments) {}
