package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.media.enums.MediaStatus;
import java.time.Instant;
import lombok.Data;

@Data
public class ChecklistEvidenceResponse {
    private String evidenceId;
    private String mediaId;
    private String executionId;
    private String attachedBy;
    private Instant attachedAt;
    private boolean selectedForCustomer;
    private String selectedBy;
    private Instant selectedAt;
    private String note;
    private Long version;
    private String mediaType;
    private String contentType;
    private String fileName;
    private MediaStatus mediaStatus;
    private String sourceType;
    private Instant capturedAt;
    private Instant sourceCapturedAt;
    private Instant validatedAt;
    private boolean eligibleForOperationalReadiness;
    private boolean eligibleForFinalApproval;
    private String ineligibilityReason;
    private String previewUrl;
    private Instant urlExpiresAt;
}
