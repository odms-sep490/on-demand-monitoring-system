package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.constraints.*;
import java.util.List;

public record ChecklistCustomerSelectionRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Size(max = 1000) List<@NotBlank String> evidenceIds,
        boolean confirmSurplus) {}
