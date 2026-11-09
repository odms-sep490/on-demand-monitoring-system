package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.PricingCategory;
import java.math.BigDecimal;
import java.util.List;

/** Explainable price of one checklist. Every amount is a component that is billed exactly once. */
public record ChecklistPriceBreakdown(String checklistId, String content, String pricingProfile, boolean custom,
        int includedImageCount, int includedVideoCount, int requestedImageCount, int requestedVideoCount,
        int extraImageCount, int extraVideoCount, Integer standardVideoDurationSeconds,
        List<Line> lines, BigDecimal subtotal, boolean quotePending) {

    /** Priced by the policy (nothing waits for the Manager). */
    public ChecklistPriceBreakdown(String checklistId, String content, String pricingProfile, boolean custom,
            int includedImageCount, int includedVideoCount, int requestedImageCount, int requestedVideoCount,
            int extraImageCount, int extraVideoCount, Integer standardVideoDurationSeconds, List<Line> lines, BigDecimal subtotal) {
        this(checklistId, content, pricingProfile, custom, includedImageCount, includedVideoCount, requestedImageCount,
                requestedVideoCount, extraImageCount, extraVideoCount, standardVideoDurationSeconds, lines, subtotal, false);
    }

    /**
     * The same checklist done {@code times} times: every line bills {@code times} times its quantity at the same unit price,
     * so the figures stay explainable (quantity x unit = amount). The per-visit media counts are not multiplied.
     */
    public ChecklistPriceBreakdown times(int times) {
        if (times <= 1) return this;
        BigDecimal factor = BigDecimal.valueOf(times);
        List<Line> scaled = lines.stream().map(line -> new Line(line.component(), line.category(), line.description(),
                line.quantity().multiply(factor), line.unitPrice(), line.amount().multiply(factor), line.tierFrom(),
                line.tierTo(), line.blockSize(), line.rounding())).toList();
        return new ChecklistPriceBreakdown(checklistId, content, pricingProfile, custom, includedImageCount, includedVideoCount,
                requestedImageCount, requestedVideoCount, extraImageCount, extraVideoCount, standardVideoDurationSeconds,
                scaled, subtotal.multiply(factor), quotePending);
    }

    /** @param component stable machine key: BASE, CUSTOM_BASE, DIFFICULTY, INCLUDED_IMAGE, INCLUDED_VIDEO, EXTRA_IMAGE, EXTRA_VIDEO. */
    public record Line(String component, PricingCategory category, String description, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal amount, Integer tierFrom, Integer tierTo, Integer blockSize, String rounding) {}
}
