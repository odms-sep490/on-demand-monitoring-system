package com.ondemandmonitoring.delivery.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveChecklistDeliveryDraftRequest(
        @NotNull Long expectedDraftVersion,
        Long expectedDeliveryVersion,
        String baseReviewRoundId,
        String caseId,
        @Size(max = 2000) String notes,
        @NotNull @Size(max = 100) List<@Valid ChecklistOperation> checklists) {

    public record ChecklistOperation(
            @NotNull String orderChecklistItemId,
            @Size(max = 1000) List<String> addMediaAssetIds,
            @Size(max = 100) List<String> removeRoundEvidenceIds,
            @Size(max = 100) List<Replacement> replacements) {}

    public record Replacement(String sourceRoundEvidenceId, String newMediaAssetId) {}
}
