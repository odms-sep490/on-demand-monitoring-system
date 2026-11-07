package com.ondemandmonitoring.delivery.dto.response;

import java.time.Instant;
import java.util.List;

public record ResultReviewResponse(String reviewRoundId, int roundNumber, Long roundVersion, Instant publishedAt,
        long checklistRevision, boolean canSubmitRevision, boolean canAcceptResult, String blockedReason,
        RevisionSummary existingRequest, int page, int size, long totalItems, List<Item> items) {
    public record RevisionSummary(String revisionRequestId, String ticketId, String ticketCode) {}
    public record Item(String roundItemId, String orderChecklistItemId, String title, String description,
            List<String> executionSteps, List<String> inputRequirements, List<String> expectedOutputs,
            String missionId, String missionCode, List<Evidence> evidence,
            boolean changedInRound, boolean canRequestRevision) {}
    public record Evidence(String roundEvidenceId, String mediaAssetId, String type, String fileName,
            Long size, Instant capturedAt, String url, Instant urlExpiresAt, boolean downloadable,
            String accessReason, String thumbnailUrl, String previewUrl) {}
}
