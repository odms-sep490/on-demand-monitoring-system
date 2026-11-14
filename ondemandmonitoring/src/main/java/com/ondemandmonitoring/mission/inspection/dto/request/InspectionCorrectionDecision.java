package com.ondemandmonitoring.mission.inspection.dto.request;

import jakarta.validation.constraints.*;

public record InspectionCorrectionDecision(
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotBlank @Size(max = 2000) String note,
    @NotBlank String reviewToken) {}
