package com.ondemandmonitoring.order.dto.request;

import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import jakarta.validation.constraints.NotNull;

public record LegalReviewResolutionRequest(
        @NotNull LegalReviewStatus status,
        String note) {
}
