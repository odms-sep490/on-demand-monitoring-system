package com.ondemandmonitoring.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record MissionPaymentAllocationItem(
        @NotBlank String missionId,
        @NotNull @DecimalMin("0.01") BigDecimal amount) {}
