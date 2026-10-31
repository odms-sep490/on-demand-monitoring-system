package com.ondemandmonitoring.finance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CommercialExpirationSettingsRequest(
        @NotNull @Min(1) @Max(8760) Integer quotationAcceptanceTimeoutHours,
        @NotNull @Min(1) @Max(8760) Integer depositPaymentTimeoutHours) {}
