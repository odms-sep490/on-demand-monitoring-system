package com.ondemandmonitoring.checklist.dto.response;

import java.time.Instant;
import lombok.*;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;

@Getter
@Setter
@NoArgsConstructor
public class ChecklistResponse {
    private String id;
    private String content;
    private String description;
    private java.util.List<String> executionSteps;
    private java.util.List<String> inputRequirements;
    private java.util.List<String> expectedOutputs;
    private Boolean isActive;
    private ChecklistDifficulty difficulty;
    private java.math.BigDecimal basePrice;
    private int includedImageCount;
    private int includedVideoCount;
    private Integer standardVideoDurationSeconds;
    private String pricingProfile;
    private boolean areaPricingEnabled;
    private boolean lengthPricingEnabled;
    private boolean altitudePricingEnabled;
    private java.math.BigDecimal includedAreaHa;
    private java.math.BigDecimal includedLengthKm;
    private java.math.BigDecimal includedAltitudeM;
    /** How many services currently include this checklist; editing it changes all of them. */
    private long usedByServiceCount;
    /** How many order lines were taken from it. Existing orders keep their own frozen copy. */
    private long usedByOrderCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
}
