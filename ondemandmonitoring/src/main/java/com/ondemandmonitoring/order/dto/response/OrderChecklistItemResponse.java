package com.ondemandmonitoring.order.dto.response;

import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;

@Getter
@Setter
@NoArgsConstructor
public class OrderChecklistItemResponse {
    private String id;
    private String sourceChecklistId;
    private String content;
    private String description;
    private java.util.List<String> executionSteps;
    private java.util.List<String> inputRequirements;
    private java.util.List<String> expectedOutputs;
    private int displayOrder;
    private OrderChecklistSourceType sourceType;
    private ChecklistReviewStatus reviewStatus;
    private String managerNote;
    private ChecklistDifficulty difficultySnapshot;
    private int evidencePolicyVersion;
    private int minimumEvidenceCount;
    private RequiredEvidenceType requiredEvidenceType;
    private int minimumImageCount;
    private int minimumVideoCount;
    private int pricingSnapshotVersion;
    private java.math.BigDecimal basePriceSnapshot;
    private int includedImageCountSnapshot;
    private int includedVideoCountSnapshot;
    private int requestedImageCount;
    private int requestedVideoCount;
    private Integer standardVideoDurationSnapshot;
    private String pricingProfileSnapshot;
    private boolean areaPricingEnabledSnapshot;
    private boolean lengthPricingEnabledSnapshot;
    private boolean altitudePricingEnabledSnapshot;
    private java.math.BigDecimal includedAreaHaSnapshot;
    private java.math.BigDecimal includedLengthKmSnapshot;
    private java.math.BigDecimal includedAltitudeMSnapshot;
    /** Customer-added rows require an explicit Manager price review. */
    private boolean managerQuoteRequired;
    private java.math.BigDecimal managerQuotedPrice;
    private java.math.BigDecimal systemCalculatedPrice;
    private String managerPriceAdjustmentReason;
    private java.math.BigDecimal managerApprovedAmount;
    private java.math.BigDecimal finalAmount;
    private java.math.BigDecimal priceDifferenceAmount;
    private java.math.BigDecimal priceDifferencePercent;
    private boolean required;
    private long checklistRevision;
    private Long sourceChecklistVersion;
}
