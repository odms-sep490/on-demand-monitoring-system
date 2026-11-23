package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChecklistImageContentAssessmentRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Pattern(regexp = "vi|en") String language) {}
