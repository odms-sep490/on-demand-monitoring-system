package com.ondemandmonitoring.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import com.ondemandmonitoring.pricing.enums.PricingAdjustmentType;

public record QuoteDraftRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 19, fraction = 0) BigDecimal packagePrice,
        @NotNull @DecimalMin("0") @Digits(integer = 19, fraction = 0) BigDecimal discountAmount,
        @NotNull @Digits(integer = 19, fraction = 0) BigDecimal adjustmentAmount,
        @Size(max = 2000) String managerNote,
        Map<String, @NotNull @DecimalMin("0") @Digits(integer = 19, fraction = 0) BigDecimal> additionalUnitPrices,
        @Size(max = 2000) String adjustmentReason,
        PricingAdjustmentType adjustmentType,
        Long expectedPricingRevision,
        /** Version of the pricing policy the Manager was looking at; null skips the check. */
        Integer expectedPolicyVersion,
        /** Checklist revision the Manager was looking at; null skips the check. */
        Long expectedChecklistRevision) {
    public QuoteDraftRequest(BigDecimal packagePrice, BigDecimal discountAmount, BigDecimal adjustmentAmount,
            String managerNote, Map<String, BigDecimal> additionalUnitPrices, String adjustmentReason,
            PricingAdjustmentType adjustmentType, Long expectedPricingRevision, Integer expectedPolicyVersion) {
        this(packagePrice, discountAmount, adjustmentAmount, managerNote, additionalUnitPrices, adjustmentReason,
                adjustmentType, expectedPricingRevision, expectedPolicyVersion, null);
    }
    public QuoteDraftRequest(BigDecimal packagePrice, BigDecimal discountAmount, BigDecimal adjustmentAmount,
            String managerNote, Map<String, BigDecimal> additionalUnitPrices, String adjustmentReason,
            PricingAdjustmentType adjustmentType, Long expectedPricingRevision) {
        this(packagePrice, discountAmount, adjustmentAmount, managerNote, additionalUnitPrices, adjustmentReason,
                adjustmentType, expectedPricingRevision, null, null);
    }
    public QuoteDraftRequest(BigDecimal packagePrice, BigDecimal discountAmount, BigDecimal adjustmentAmount,
            String managerNote, Map<String, BigDecimal> additionalUnitPrices) {
        this(packagePrice, discountAmount, adjustmentAmount, managerNote, additionalUnitPrices, managerNote,
                PricingAdjustmentType.CORRECTION, null, null, null);
    }
}
