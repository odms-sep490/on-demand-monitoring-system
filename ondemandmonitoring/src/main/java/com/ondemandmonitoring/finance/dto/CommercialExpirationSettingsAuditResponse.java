package com.ondemandmonitoring.finance.dto;

import java.time.Instant;

public record CommercialExpirationSettingsAuditResponse(
        String id,
        String settingName,
        int previousValue,
        int newValue,
        String actor,
        Instant changedAt) {}
