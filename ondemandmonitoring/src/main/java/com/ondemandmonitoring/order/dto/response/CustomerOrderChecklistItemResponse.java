package com.ondemandmonitoring.order.dto.response;

import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Customer-safe checklist projection. Internal system/manager calculations never cross this boundary. */
@Getter
@Setter
@NoArgsConstructor
public class CustomerOrderChecklistItemResponse {
    private String id;
    private String sourceChecklistId;
    private String content;
    private String description;
    private java.util.List<String> executionSteps;
    private java.util.List<String> inputRequirements;
    private java.util.List<String> expectedOutputs;
    /** Current order metadata; custom text is supplied separately from the quote snapshot. */
    private long guidanceVersion;
    private Long guidanceConfirmedVersion;
    private String guidanceUpdatedById;
    private java.time.Instant guidanceUpdatedAt;
    private boolean guidanceReady;
    private boolean canEditGuidance;
    private String guidanceBlockedReason;
    private int displayOrder;
    private OrderChecklistSourceType sourceType;
    private ChecklistReviewStatus reviewStatus;
    private int evidencePolicyVersion;
    private int pricingSnapshotVersion;
    private int minimumEvidenceCount;
    private RequiredEvidenceType requiredEvidenceType;
    private int minimumImageCount;
    private int minimumVideoCount;
    private int includedImageCountSnapshot;
    private int includedVideoCountSnapshot;
    private int requestedImageCount;
    private int requestedVideoCount;
    private ChecklistPricingStatus pricingStatus;
    private boolean priceAvailable;
    private BigDecimal approvedPrice;
    private boolean required;
    private long checklistRevision;
    private Long sourceChecklistVersion;
}
