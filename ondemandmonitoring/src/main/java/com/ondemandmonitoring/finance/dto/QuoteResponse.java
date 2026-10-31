package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.QuoteStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.ondemandmonitoring.pricing.enums.*;

public record QuoteResponse(String id, String orderId, int version, QuoteStatus status,
                            BigDecimal packagePrice, BigDecimal additionalAmount,
                            BigDecimal discountAmount, BigDecimal adjustmentAmount,
                            BigDecimal totalAmount, String managerNote, String approvedByName,
                            Instant approvedAt, Instant acceptedAt, List<QuoteItemResponse> items,
                            String pricingPolicyId, int pricingPolicyVersion, BigDecimal systemCalculatedAmount,
                            BigDecimal managerAdjustmentAmount, PricingAdjustmentType adjustmentType,
                            String adjustmentReason, String adjustmentRequestedById, String adjustmentRequestedByName,
                            Instant adjustmentRequestedAt, PricingApprovalStatus pricingApprovalStatus,
                            String pricingApprovedByName, Instant pricingApprovedAt, String pricingApprovalNote,
                            long pricingRevision, List<PricingRiskFlag> riskFlags,
                            long checklistRevision, ChecklistGuidanceSnapshot checklistGuidanceSnapshot) {
    public QuoteResponse(String id, String orderId, int version, QuoteStatus status,
                         BigDecimal packagePrice, BigDecimal additionalAmount,
                         BigDecimal discountAmount, BigDecimal adjustmentAmount,
                         BigDecimal totalAmount, String managerNote, String approvedByName,
                         Instant approvedAt, Instant acceptedAt, List<QuoteItemResponse> items,
                         String pricingPolicyId, int pricingPolicyVersion, BigDecimal systemCalculatedAmount,
                         BigDecimal managerAdjustmentAmount, PricingAdjustmentType adjustmentType,
                         String adjustmentReason, String adjustmentRequestedById, String adjustmentRequestedByName,
                         Instant adjustmentRequestedAt, PricingApprovalStatus pricingApprovalStatus,
                         String pricingApprovedByName, Instant pricingApprovedAt, String pricingApprovalNote,
                         long pricingRevision, List<PricingRiskFlag> riskFlags) {
        this(id, orderId, version, status, packagePrice, additionalAmount, discountAmount, adjustmentAmount,
                totalAmount, managerNote, approvedByName, approvedAt, acceptedAt, items,
                pricingPolicyId, pricingPolicyVersion, systemCalculatedAmount, managerAdjustmentAmount,
                adjustmentType, adjustmentReason, adjustmentRequestedById, adjustmentRequestedByName,
                adjustmentRequestedAt, pricingApprovalStatus, pricingApprovedByName, pricingApprovedAt,
                pricingApprovalNote, pricingRevision, riskFlags, 1L, null);
    }
}
