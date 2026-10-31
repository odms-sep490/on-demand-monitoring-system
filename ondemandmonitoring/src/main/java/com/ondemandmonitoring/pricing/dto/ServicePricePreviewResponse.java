package com.ondemandmonitoring.pricing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Explainable price of a service or of a customer's checklist selection.
 * {@code total = checklistSubtotal + sum(addOns)}. The checklists are the only price source ({@code priceSource} CHECKLISTS) or the
 * service is UNPRICED; {@code packageFee} is always 0. Customer previews may include the shared area, length and altitude context; each checklist decides which
 * dimensions apply. Deterministic order-level factors selected by the customer are returned as {@code addOns}.
 * {@code occurrences} is how many times the work is carried out; every figure already includes it (1 for a single visit).
 */
public record ServicePricePreviewResponse(String serviceId, String serviceName, String currency, String policyId,
        Integer policyVersion, boolean draftPolicy, String priceSource, BigDecimal packageFee, List<ChecklistPriceBreakdown> checklists,
        BigDecimal checklistSubtotal, BigDecimal total, boolean complete, List<String> warnings, boolean quotePending,
        List<AddOn> addOns, int occurrences) {

    /** One visit: the figures are per visit. */
    public ServicePricePreviewResponse(String serviceId, String serviceName, String currency, String policyId,
            Integer policyVersion, boolean draftPolicy, String priceSource, BigDecimal packageFee, List<ChecklistPriceBreakdown> checklists,
            BigDecimal checklistSubtotal, BigDecimal total, boolean complete, List<String> warnings, boolean quotePending,
            List<AddOn> addOns) {
        this(serviceId, serviceName, currency, policyId, policyVersion, draftPolicy, priceSource, packageFee, checklists,
                checklistSubtotal, total, complete, warnings, quotePending, addOns, 1);
    }

    /** An order-level extra the customer asked for, priced by a policy rule. */
    public record AddOn(String key, String description, BigDecimal amount) {}


    public ServicePricePreviewResponse(String serviceId, String serviceName, String currency, String policyId,
            Integer policyVersion, boolean draftPolicy, String priceSource, BigDecimal packageFee, List<ChecklistPriceBreakdown> checklists,
            BigDecimal checklistSubtotal, BigDecimal total, boolean complete, List<String> warnings) {
        this(serviceId, serviceName, currency, policyId, policyVersion, draftPolicy, priceSource, packageFee, checklists,
                checklistSubtotal, total, complete, warnings, checklists.stream().anyMatch(ChecklistPriceBreakdown::quotePending),
                List.of());
    }
}
