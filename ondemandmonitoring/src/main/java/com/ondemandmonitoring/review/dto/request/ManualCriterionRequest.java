package com.ondemandmonitoring.review.dto.request;

import com.ondemandmonitoring.review.enums.CriterionResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualCriterionRequest(@NotNull CriterionResult result, @Size(max = 1000) String note) {}
