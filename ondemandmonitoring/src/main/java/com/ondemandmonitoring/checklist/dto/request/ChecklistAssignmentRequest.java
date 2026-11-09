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
    @DecimalMin("0") @Digits(integer = 13, fraction = 0)
    private java.math.BigDecimal basePriceOverride;
    @Min(0) @Max(500)
    private Integer includedImageCountOverride;
    @Min(0) @Max(500)
    private Integer includedVideoCountOverride;
    @Size(max = 60) @Pattern(regexp = "^[A-Za-z0-9_]*$", message = "pricingProfile may contain letters, digits and underscore only")
    private String pricingProfileOverride;
}
