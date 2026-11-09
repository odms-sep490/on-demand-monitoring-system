package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.PricingPolicyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PricingPolicyResponse(String id, String name, int version, PricingPolicyStatus status, String currency,
        Instant effectiveFrom, Instant effectiveTo, BigDecimal managerMaxDiscountPercent,
        BigDecimal managerMaxMarkupPercent, BigDecimal highRiskDiscountPercent, BigDecimal minimumPriceRatio,
        BigDecimal minimumPrice, List<PricingRuleResponse> rules) {}
