package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.dto.request.SaveChecklistDeliveryDraftRequest;
import java.time.Instant;
import java.util.List;

public record ChecklistDeliveryDraftResponse(String orderId, long version, String caseId,
        String baseReviewRoundId, Long expectedDeliveryVersion, String notes,
        List<SaveChecklistDeliveryDraftRequest.ChecklistOperation> checklists,
        Instant updatedAt, boolean stale) {}
