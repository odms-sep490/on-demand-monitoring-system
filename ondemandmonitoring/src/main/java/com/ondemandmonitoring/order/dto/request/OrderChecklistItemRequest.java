package com.ondemandmonitoring.order.dto.request;

import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class OrderChecklistItemRequest {
    @Size(min = 1, max = 255)
    private String sourceChecklistId;
    @PositiveOrZero
    private Long expectedChecklistVersion;
    @Size(max = 500)
    private String contentOverride;
    @Min(value = 1, message = "minimumEvidenceCount must be at least 1")
    @Max(value = 50, message = "minimumEvidenceCount must not exceed 50")
    private Integer minimumEvidenceCount;
    private RequiredEvidenceType requiredEvidenceType;
    /** Media the customer wants for this checklist; the backend prices it and derives the evidence thresholds. */
    @Min(0) @Max(500)
    private Integer requestedImageCount;
    @Min(0) @Max(500)
    private Integer requestedVideoCount;
}
