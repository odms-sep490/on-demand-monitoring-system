package com.ondemandmonitoring.delivery.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ChecklistDeliveryPublishRequest(
        @NotNull UUID clientRequestId,
        Long expectedDeliveryVersion,
        String baseReviewRoundId,
        Long expectedReviewRoundVersion,
        @Size(max = 2000) String notes,
        boolean confirmSurplus,
        String surplusConfirmationFingerprint,
        @NotEmpty @Size(max = 100) List<@Valid ChecklistSelection> checklists) {

    public record ChecklistSelection(
            @NotBlank String orderChecklistItemId,
            @Size(max = 1000) List<@NotBlank String> addMediaAssetIds,
            @Size(max = 100) List<@NotBlank String> removeRoundEvidenceIds,
            @Size(max = 100) List<@Valid Replacement> replacements) {}

    public record Replacement(@NotBlank String sourceRoundEvidenceId, @NotBlank String newMediaAssetId) {}
}
