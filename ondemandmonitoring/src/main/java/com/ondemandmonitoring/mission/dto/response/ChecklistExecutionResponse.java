package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import java.time.Instant;
import lombok.Data;

@Data
public class ChecklistExecutionResponse {
    private String id;
    private String orderChecklistItemId;
    private String content;
    private String description;
    private java.util.List<String> executionSteps;
    private java.util.List<String> inputRequirements;
    private java.util.List<String> expectedOutputs;
    private int displayOrder;
    private OrderChecklistSourceType sourceType;
    private ChecklistExecutionStatus executionStatus;
    private ChecklistAssessmentStatus assessmentStatus;
    private String assessmentNote;
    private String assessedBy;
    private Instant assessedAt;
    private String observation;
    private String unableToVerifyReason;
    private Instant startedAt;
    private Instant completedAt;
    private String lastModifiedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
    private int evidencePolicyVersion;
    private int minimumEvidenceCount;
    private RequiredEvidenceType requiredEvidenceType;
    private int eligibleEvidenceCount;
    /** Policy V3: the image and the video threshold must each be met. Both 0 for policies V0-V2. */
    private int minimumImageCount;
    private int minimumVideoCount;
    private int eligibleImageCount;
    private int eligibleVideoCount;
    private boolean evidenceRequirementSatisfied;
    private boolean evidenceReady;
    private boolean readyForFinalApproval;
    private boolean customerSelectionEditable;
    private java.util.List<String> blockingReasons = java.util.List.of();
    private java.util.List<ChecklistEvidenceResponse> evidence = java.util.List.of();
}
