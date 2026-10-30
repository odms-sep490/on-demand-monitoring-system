package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.QuoteStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Customer-safe commercial projection. It never exposes system prices or manager adjustments. */
public record CustomerQuoteResponse(String id, String orderId, int version, QuoteStatus status,
        BigDecimal estimatedBaseAmount, BigDecimal approvedAdditionalServiceAmount,
        BigDecimal finalOrderAmount, boolean pricingFinalized,
        Instant approvedAt, Instant acceptedAt, List<QuoteItemResponse> items,
        long checklistRevision) {
    public CustomerQuoteResponse(String id, String orderId, int version, QuoteStatus status,
            BigDecimal estimatedBaseAmount, BigDecimal approvedAdditionalServiceAmount,
            BigDecimal finalOrderAmount, boolean pricingFinalized,
            Instant approvedAt, Instant acceptedAt, List<QuoteItemResponse> items) {
        this(id, orderId, version, status, estimatedBaseAmount, approvedAdditionalServiceAmount,
                finalOrderAmount, pricingFinalized, approvedAt, acceptedAt, items, 1L);
    }
}
