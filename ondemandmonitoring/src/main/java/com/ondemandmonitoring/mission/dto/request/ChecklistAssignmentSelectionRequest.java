package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record ChecklistAssignmentSelectionRequest(
        @NotNull @PositiveOrZero Long expectedPlanRevision,
        @NotNull @Size(max = 1000) List<@NotNull @Valid Item> items) {
    public record Item(@NotBlank String orderChecklistItemId) {}
}
