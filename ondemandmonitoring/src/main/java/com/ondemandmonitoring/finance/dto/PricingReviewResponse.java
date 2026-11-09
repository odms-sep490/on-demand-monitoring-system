package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.order.dto.response.OrderChecklistItemResponse;
import java.util.List;
import java.math.BigDecimal;
import com.ondemandmonitoring.pricing.dto.PricingBreakdown;

public record PricingReviewResponse(String orderId, String orderCode, String customerName,
                                    String serviceName, BigDecimal basePackagePrice,
                                    List<OrderChecklistItemResponse> checklistItems,
                                    QuoteResponse currentQuote, List<QuoteResponse> quoteHistory,
                                    PricingBreakdown systemCalculation, String currentManagerId,
                                    /** Room the Manager has to move a customer-added price, from the policy in force. */
                                    BigDecimal managerMaxDiscountPercent, BigDecimal managerMaxMarkupPercent,
                                    long checklistRevision) {
    public PricingReviewResponse(String orderId, String orderCode, String customerName,
                                 String serviceName, BigDecimal basePackagePrice,
                                 List<OrderChecklistItemResponse> checklistItems,
                                 QuoteResponse currentQuote, List<QuoteResponse> quoteHistory,
                                 PricingBreakdown systemCalculation, String currentManagerId,
                                 BigDecimal managerMaxDiscountPercent, BigDecimal managerMaxMarkupPercent) {
        this(orderId, orderCode, customerName, serviceName, basePackagePrice, checklistItems,
                currentQuote, quoteHistory, systemCalculation, currentManagerId,
                managerMaxDiscountPercent, managerMaxMarkupPercent, 1L);
    }
}
