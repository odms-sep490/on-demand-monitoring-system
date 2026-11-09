package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PricingRuleRequest(@NotNull PricingCategory category, @NotNull PricingCalculationType calculationType,
        @NotBlank @Size(max = 80) String ruleKey, @NotBlank @Size(max = 200) String description,
        BigDecimal minValue, BigDecimal maxValue, BigDecimal fixedAmount, BigDecimal baseAmount, BigDecimal percentage,
        BigDecimal unitSize, BigDecimal multiplier, @Min(0) int priority, boolean enabled,
        @Size(max = 60) String pricingProfile, MediaPricingComponent mediaComponent,
        @Min(0) @Max(500) Integer includedQuantity, PricingRounding roundingMode,
        @Size(max = 36) String checklistDefinitionId) {
    public PricingRuleRequest(PricingCategory category, PricingCalculationType calculationType,
            String ruleKey, String description, BigDecimal minValue, BigDecimal maxValue,
            BigDecimal fixedAmount, BigDecimal baseAmount, BigDecimal percentage, BigDecimal unitSize,
            BigDecimal multiplier, int priority, boolean enabled, String pricingProfile,
            MediaPricingComponent mediaComponent, Integer includedQuantity, PricingRounding roundingMode) {
        this(category, calculationType, ruleKey, description, minValue, maxValue, fixedAmount, baseAmount,
                percentage, unitSize, multiplier, priority, enabled, pricingProfile, mediaComponent,
                includedQuantity, roundingMode, null);
    }
}
