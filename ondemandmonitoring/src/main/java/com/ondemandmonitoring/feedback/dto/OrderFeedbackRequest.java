package com.ondemandmonitoring.feedback.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OrderFeedbackRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Min(1) @Max(5) Integer qualityRating,
        @Min(1) @Max(5) Integer punctualityRating,
        @Min(1) @Max(5) Integer communicationRating,
        @Size(max = 2000) String comment) {}
