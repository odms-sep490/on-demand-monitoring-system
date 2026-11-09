package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record ChecklistReorderRequest(@NotNull @Size(max = 100) List<@NotNull @Valid Item> items) {
    public record Item(@NotBlank String checklistId, @NotNull @Min(0) Long expectedVersion) {}
}
