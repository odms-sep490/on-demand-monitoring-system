package com.ondemandmonitoring.mission.dto.response;
import com.ondemandmonitoring.media.enums.MediaStatus;
import java.time.Instant;
import java.util.List;
public record ChecklistEvidenceCandidate(String mediaId, String fileName, String mediaType, String contentType,
        MediaStatus status, String sourceType, Instant capturedAt, Instant validatedAt,
        boolean attachable, boolean eligibleForOperationalReadiness, boolean eligibleForFinalApproval,
        String ineligibilityReason, String previewUrl, String thumbnailUrl, Instant urlExpiresAt, List<String> alreadyAttachedExecutionIds) {}
