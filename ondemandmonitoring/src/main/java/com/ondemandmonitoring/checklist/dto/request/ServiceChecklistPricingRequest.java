package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Replaces the association-level pricing overrides; a null field inherits the checklist definition. */
@Getter
@Setter
@NoArgsConstructor
public class ServiceChecklistPricingRequest {
    @DecimalMin("0") @Digits(integer = 13, fraction = 0)
    private BigDecimal basePriceOverride;
    @Min(0) @Max(500)
    private Integer includedImageCountOverride;
    @Min(0) @Max(500)
    private Integer includedVideoCountOverride;
    @Size(max = 60) @Pattern(regexp = "^[A-Za-z0-9_]*$", message = "pricingProfile may contain letters, digits and underscore only")
    private String pricingProfileOverride;
    private Boolean required;
    /** Optimistic-lock guard for the association row. */
    private Long expectedVersion;
}
