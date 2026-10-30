package com.ondemandmonitoring.order.dto.request;

import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderChecklistUpdateItemRequest {
    @Size(max = 255)
    private String existingItemId;

    @Size(max = 255)
    private String sourceChecklistId;

    @PositiveOrZero
    private Long expectedChecklistVersion;

    private String contentOverride;
    private Integer minimumEvidenceCount;
    private RequiredEvidenceType requiredEvidenceType;
    private Integer requestedImageCount;
    private Integer requestedVideoCount;
}

