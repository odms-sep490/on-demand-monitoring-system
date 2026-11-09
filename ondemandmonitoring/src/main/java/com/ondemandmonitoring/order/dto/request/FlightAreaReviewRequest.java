package com.ondemandmonitoring.order.dto.request;

import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import jakarta.validation.constraints.NotNull;

public record FlightAreaReviewRequest(
        @NotNull LegalReviewStatus status,
        Boolean requestedScopeFeasible,
        String reason,
        String note) {
}
