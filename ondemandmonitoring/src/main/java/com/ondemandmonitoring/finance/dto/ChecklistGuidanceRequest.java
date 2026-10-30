package com.ondemandmonitoring.finance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record ChecklistGuidanceRequest(
        @NotNull @PositiveOrZero Long expectedChecklistRevision,
        @NotNull @PositiveOrZero Long expectedGuidanceVersion,
        String description, List<String> executionSteps, List<String> inputRequirements,
        List<String> expectedOutputs, boolean confirm) {}
