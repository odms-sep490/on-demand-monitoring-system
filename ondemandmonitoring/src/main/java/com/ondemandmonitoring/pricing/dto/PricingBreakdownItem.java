package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.PricingCategory;
import java.math.BigDecimal;

public record PricingBreakdownItem(PricingCategory category, String description,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, String orderChecklistItemId) {
    public PricingBreakdownItem(PricingCategory category, String description,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        this(category, description, quantity, unitPrice, amount, null);
    }
}
