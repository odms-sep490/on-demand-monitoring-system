package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;

@Getter
@Setter
@NoArgsConstructor
public class ChecklistRequest {
    @NotBlank
    @Size(max = 500)
    private String content;
    @Size(max = 4000)
    private String description;
    @Size(max = 30)
    private java.util.List<@NotBlank @Size(max = 500) String> executionSteps;
    @Size(max = 30)
    private java.util.List<@NotBlank @Size(max = 500) String> inputRequirements;
    @Size(max = 30)
    private java.util.List<@NotBlank @Size(max = 500) String> expectedOutputs;
    private ChecklistDifficulty difficulty;
    /** Price of the inspection work itself (VND, whole amount); media is priced by the pricing profile. */
    @jakarta.validation.constraints.DecimalMin("0")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 0)
    private java.math.BigDecimal basePrice;
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(500)
    private Integer includedImageCount;
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(500)
    private Integer includedVideoCount;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(7200)
    private Integer standardVideoDurationSeconds;
    @Size(max = 60) @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9_]*$", message = "pricingProfile may contain letters, digits and underscore only")
    private String pricingProfile;
    private Boolean areaPricingEnabled;
    private Boolean lengthPricingEnabled;
    private Boolean altitudePricingEnabled;
    @jakarta.validation.constraints.DecimalMin("0")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 3)
    private java.math.BigDecimal includedAreaHa;
    @jakarta.validation.constraints.DecimalMin("0")
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 3)
    private java.math.BigDecimal includedLengthKm;
    @jakarta.validation.constraints.DecimalMin("0")
    @jakarta.validation.constraints.Digits(integer = 6, fraction = 2)
    private java.math.BigDecimal includedAltitudeM;
}
