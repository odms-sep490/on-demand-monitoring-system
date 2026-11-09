package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Immutable policy V1: provenance is never inferred from filename, storage key or upload method. */
@Component
public class ChecklistEvidencePolicy {
    private static final Set<String> ELIGIBLE_SOURCE_TYPES = Set.of("DRONE_CAMERA", "SATELLITE_SNAPSHOT", "MANUAL_UPLOAD");

    public int eligibleDistinctCount(Collection<MissionChecklistEvidence> links, String executionId,
            RequiredEvidenceType requiredType, boolean finalApproval) {
        return (int) links.stream()
                .filter(link -> link.getDetachedAt() == null)
                .filter(link -> Objects.equals(executionId, link.getMissionChecklistExecution().getId()))
                .filter(link -> mediaTypeMatches(requiredType, link.getMediaAsset().getType()))
                .filter(link -> ineligibilityReason(link.getMediaAsset(), finalApproval) == null)
                .map(link -> contentKey(link.getMediaAsset()))
                .distinct()
                .count();
    }

    /** Identical files count once, however many times they were uploaded. */
    private static String contentKey(MediaAsset media) {
        String checksum = media.getChecksumSha256();
        return checksum == null || checksum.isBlank() ? "id:" + media.getId() : "sha:" + checksum.toLowerCase();
    }

    public boolean mediaTypeMatches(RequiredEvidenceType requiredType, String mediaType) {
        if (requiredType == null) return false;
        return switch (requiredType) {
            case IMAGE -> "IMAGE".equals(mediaType);
            case VIDEO -> "VIDEO".equals(mediaType);
            case IMAGE_OR_VIDEO, IMAGE_AND_VIDEO -> Set.of("IMAGE", "VIDEO").contains(mediaType);
        };
    }

    public String ineligibilityReason(MediaAsset media, boolean finalApproval) {
        if (media.getStorageStatus() != com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE) return "MEDIA_NOT_VALIDATED";
        if (!isEligibleSource(media)) return "EVIDENCE_SOURCE_NOT_ELIGIBLE";
        if (!Set.of("IMAGE", "VIDEO").contains(media.getType() == null ? "" : media.getType())) return "EVIDENCE_TYPE_NOT_ELIGIBLE";
        if (media.getMediaStatus() == MediaStatus.REJECTED) return "MEDIA_REJECTED";
        if (media.getValidatedAt() == null || media.getValidationError() != null) return "MEDIA_NOT_VALIDATED";
        if (media.getMediaStatus() == MediaStatus.AVAILABLE) return null;
        if (media.getMediaStatus() == MediaStatus.PENDING_MANAGER_APPROVAL)
            return finalApproval ? "MEDIA_APPROVAL_REQUIRED" : null;
        return "MEDIA_NOT_VALIDATED";
    }
    public boolean attachable(MediaAsset media) {
        return isEligibleSource(media)
                && media.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE
                && Set.of("IMAGE", "VIDEO").contains(media.getType() == null ? "" : media.getType())
                && media.getMediaStatus() != null && media.getMediaStatus() != MediaStatus.REJECTED;
    }

    private boolean isEligibleSource(MediaAsset media) {
        return media.getSourceType() != null && ELIGIBLE_SOURCE_TYPES.contains(media.getSourceType());
    }
}
