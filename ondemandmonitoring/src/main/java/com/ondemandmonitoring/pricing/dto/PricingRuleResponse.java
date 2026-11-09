package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.*;
import java.math.BigDecimal;

public record PricingRuleResponse(String id, PricingCategory category, PricingCalculationType calculationType,
        String ruleKey, String description, BigDecimal minValue, BigDecimal maxValue, BigDecimal fixedAmount, BigDecimal baseAmount,
        BigDecimal percentage, BigDecimal unitSize, BigDecimal multiplier, int priority, boolean enabled,
        String pricingProfile, MediaPricingComponent mediaComponent, Integer includedQuantity, PricingRounding roundingMode,
        String checklistDefinitionId, String checklistDefinitionName) {}
