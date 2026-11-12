package com.ondemandmonitoring.finance.dto;

import java.time.Duration;
import java.time.Instant;

public record CommercialExpirationSettingsResponse(
        int quotationAcceptanceTimeoutHours,
        int depositPaymentTimeoutHours,
        int missionPaymentTimeoutHours,
        String updatedBy,
        Instant updatedAt) {

    public CommercialExpirationSettingsResponse(int quotationAcceptanceTimeoutHours,
            int depositPaymentTimeoutHours, String updatedBy, Instant updatedAt) {
        this(quotationAcceptanceTimeoutHours, depositPaymentTimeoutHours, 24, updatedBy, updatedAt);
    }

    public Duration quotationAcceptanceTimeout() {
        return Duration.ofHours(quotationAcceptanceTimeoutHours);
    }

    public Duration depositPaymentTimeout() {
        return Duration.ofHours(depositPaymentTimeoutHours);
    }

    public Duration missionPaymentTimeout() {
        return Duration.ofHours(missionPaymentTimeoutHours);
    }
}
