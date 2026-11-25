package com.ondemandmonitoring.checklist.dto.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.*;

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
    /** Omit on update to keep the existing criteria; send [] to disable assessment. */
    private List<String> imageAssessmentCriteria;
    /** Base price in whole VND for the configured standard area. */
    @jakarta.validation.constraints.DecimalMin("0")
    @jakarta.validation.constraints.Digits(integer = 13, fraction = 0)
    private java.math.BigDecimal basePrice;
    @jakarta.validation.constraints.DecimalMin("0.0001")
    @jakarta.validation.constraints.Digits(integer = 11, fraction = 3)
    private java.math.BigDecimal standardAreaM2;
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(500)
    private Integer includedImageCount;
    @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(500)
    private Integer includedVideoCount;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(7200)
    private Integer standardVideoDurationSeconds;

    @JsonSetter("imageAssessmentCriteria")
    public void setImageAssessmentCriteria(List<?> criteria) {
        if (criteria != null && criteria.stream().anyMatch(value -> value != null && !(value instanceof String))) {
            throw new IllegalArgumentException("imageAssessmentCriteria must contain only strings");
        }
        imageAssessmentCriteria = criteria == null ? null : criteria.stream().map(String.class::cast).toList();
    }
}
