package com.ondemandmonitoring.finance.dto;

import java.time.Duration;
import java.time.Instant;

public record CommercialExpirationSettingsResponse(
        int quotationAcceptanceTimeoutHours,
        int depositPaymentTimeoutHours,
        String updatedBy,
        Instant updatedAt) {
    public Duration quotationAcceptanceTimeout() {
        return Duration.ofHours(quotationAcceptanceTimeoutHours);
    }

    public Duration depositPaymentTimeout() {
        return Duration.ofHours(depositPaymentTimeoutHours);
    }
}
