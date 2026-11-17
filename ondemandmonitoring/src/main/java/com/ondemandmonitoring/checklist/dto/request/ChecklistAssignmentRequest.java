package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class ChecklistAssignmentRequest {
    @NotBlank
    private String checklistId;
    @NotNull
    @Min(0)
    private Integer displayOrder = 0;
    private Boolean required;
    @Min(0) @Max(500)
    private Integer includedImageCountOverride;
    @Min(0) @Max(500)
    private Integer includedVideoCountOverride;
}
