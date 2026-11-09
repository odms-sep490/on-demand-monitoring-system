package com.ondemandmonitoring.pricing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PricingPolicyRequest(@NotBlank @Size(max = 120) String name, Instant effectiveFrom, Instant effectiveTo,
        @NotNull @DecimalMin("0") BigDecimal managerMaxDiscountPercent,
        @NotNull @DecimalMin("0") BigDecimal managerMaxMarkupPercent,
        @NotNull @DecimalMin("0") BigDecimal highRiskDiscountPercent,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal minimumPriceRatio,
        @NotNull @DecimalMin("1") BigDecimal minimumPrice,
        @NotEmpty List<@Valid PricingRuleRequest> rules) {}
