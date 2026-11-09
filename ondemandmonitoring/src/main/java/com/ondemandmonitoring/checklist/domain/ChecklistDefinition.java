package com.ondemandmonitoring.checklist.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "checklist_definitions", uniqueConstraints =
        @UniqueConstraint(name = "uk_checklist_normalized_content", columnNames = "normalized_content"))
@Getter
@Setter
@NoArgsConstructor
public class ChecklistDefinition extends BaseEntity {
    // Bootstrap identity survives administrator content edits; not an evidence policy.
    @Column(name = "seed_code", unique = true, length = 20)
    private String seedCode;
    @Column(nullable = false, length = 500)
    private String content;
    /** What the item is for and what it covers; shown to customers and managers. */
    @Column(name = "description", length = 4000)
    private String description;
    /** Ordered work steps for the field crew. */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "execution_steps", columnDefinition = "text")
    private java.util.List<String> executionSteps;
    /** What must be in place before the work starts (area, altitude, weather, equipment). */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "input_requirements", columnDefinition = "text")
    private java.util.List<String> inputRequirements;
    /** What the crew must deliver for the item to count as done. */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "expected_outputs", columnDefinition = "text")
    private java.util.List<String> expectedOutputs;
    @Column(name = "normalized_content", nullable = false, length = 1000)
    private String normalizedContent;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20, columnDefinition = "varchar(20) default 'BASIC'")
    private ChecklistDifficulty difficulty = ChecklistDifficulty.BASIC;
    /** Price of the inspection work itself, excluding the media allowance priced by the pricing profile. */
    @Column(name = "base_price", nullable = false, precision = 19, scale = 0, columnDefinition = "numeric(19,0) default 0")
    private BigDecimal basePrice = BigDecimal.ZERO;
    @Column(name = "included_image_count", nullable = false, columnDefinition = "integer default 0")
    private int includedImageCount;
    @Column(name = "included_video_count", nullable = false, columnDefinition = "integer default 0")
    private int includedVideoCount;
    @Column(name = "standard_video_duration_seconds")
    private Integer standardVideoDurationSeconds;
    /** MEDIA pricing profile key; null means the DEFAULT profile. */
    @Column(name = "pricing_profile", length = 60)
    private String pricingProfile;
    @Column(name = "area_pricing_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean areaPricingEnabled;
    @Column(name = "length_pricing_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean lengthPricingEnabled;
    @Column(name = "altitude_pricing_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean altitudePricingEnabled;
    @Column(name = "included_area_ha", nullable = false, precision = 14, scale = 3, columnDefinition = "numeric(14,3) default 0")
    private BigDecimal includedAreaHa = BigDecimal.ZERO;
    @Column(name = "included_length_km", nullable = false, precision = 14, scale = 3, columnDefinition = "numeric(14,3) default 0")
    private BigDecimal includedLengthKm = BigDecimal.ZERO;
    @Column(name = "included_altitude_m", nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) default 0")
    private BigDecimal includedAltitudeM = BigDecimal.ZERO;
}
