package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Service-specific evidence requirements; price configuration stays on the Base Checklist. */
@Getter
@Setter
@NoArgsConstructor
public class ServiceChecklistRequirementsRequest {
    @Min(0) @Max(500)
    private Integer includedImageCountOverride;
    @Min(0) @Max(500)
    private Integer includedVideoCountOverride;
    private Boolean required;
    /** Optimistic-lock guard for the association row. */
    private Long expectedVersion;
}
