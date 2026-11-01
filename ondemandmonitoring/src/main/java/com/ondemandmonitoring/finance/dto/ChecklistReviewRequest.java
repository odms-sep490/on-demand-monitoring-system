package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChecklistReviewRequest(@NotNull ChecklistReviewStatus status,
                                     @Size(max = 1000) String managerNote,
                                     Long expectedChecklistRevision) {
    public ChecklistReviewRequest(ChecklistReviewStatus status, String managerNote) {
        this(status, managerNote, null);
    }
}
