package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.enums.DeliveryEvidenceChangeType;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import java.time.Instant;
import java.util.List;

public record ManagerDeliveryChecklistsResponse(
        String orderId,
        DeliveryStatus deliveryStatus,
        Long deliveryVersion,
        String currentReviewRoundId,
        Long currentReviewRoundVersion,
        List<Checklist> checklists) {

    public record Checklist(
            String orderChecklistItemId,
            String title,
            int minimumImageCount,
            int minimumVideoCount,
            int minimumEvidenceCount,
            RequiredEvidenceType requiredEvidenceType,
            List<Asset> eligibleMedia,
            List<Asset> currentBundle) {}

    public record Asset(
            String roundEvidenceId,
            String mediaAssetId,
            String type,
            String fileName,
            Long size,
            Instant capturedAt,
            String missionId,
            String missionCode,
            String url,
            Instant urlExpiresAt,
            String accessReason,
            DeliveryEvidenceChangeType changeType) {}
}
