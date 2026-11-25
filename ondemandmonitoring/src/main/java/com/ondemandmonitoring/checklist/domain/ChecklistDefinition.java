package com.ondemandmonitoring.checklist.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "checklist_definitions", uniqueConstraints =
        @UniqueConstraint(name = "uk_checklist_normalized_content", columnNames = "normalized_content"))
@Getter
@Setter
@NoArgsConstructor
public class ChecklistDefinition extends BaseEntity {
    public static final BigDecimal DEFAULT_EXTRA_IMAGE_UNIT_PRICE = BigDecimal.valueOf(50_000);
    public static final BigDecimal DEFAULT_EXTRA_VIDEO_UNIT_PRICE = BigDecimal.valueOf(100_000);

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
    /** Null for unconfigured checklists; an empty list explicitly disables image assessment. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_assessment_criteria", columnDefinition = "jsonb")
    private List<String> imageAssessmentCriteria;
    @Column(name = "normalized_content", nullable = false, length = 1000)
    private String normalizedContent;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    /** Global price at the configured standard area, in whole VND. */
    @Column(name = "base_price", nullable = false, precision = 19, scale = 0, columnDefinition = "numeric(19,0) default 0")
    private BigDecimal basePrice = BigDecimal.ZERO;
    /** Area (m²) represented by basePrice. Null until the administrator configures the new area-based price. */
    @Column(name = "standard_area_m2", precision = 14, scale = 3)
    private BigDecimal standardAreaM2;
    @Column(name = "included_image_count", nullable = false, columnDefinition = "integer default 0")
    private int includedImageCount;
    @Column(name = "included_video_count", nullable = false, columnDefinition = "integer default 0")
    private int includedVideoCount;
    @Column(name = "extra_image_unit_price", nullable = false, precision = 19, scale = 0,
            columnDefinition = "numeric(19,0) default 50000")
    private BigDecimal extraImageUnitPrice = DEFAULT_EXTRA_IMAGE_UNIT_PRICE;
    @Column(name = "extra_video_unit_price", nullable = false, precision = 19, scale = 0,
            columnDefinition = "numeric(19,0) default 100000")
    private BigDecimal extraVideoUnitPrice = DEFAULT_EXTRA_VIDEO_UNIT_PRICE;
    @Column(name = "standard_video_duration_seconds")
    private Integer standardVideoDurationSeconds;

    /** Older JPA rows without a stored override use the current catalog default. */
    public BigDecimal getExtraImageUnitPrice() {
        return extraImageUnitPrice == null ? DEFAULT_EXTRA_IMAGE_UNIT_PRICE : extraImageUnitPrice;
    }

    /** Older JPA rows without a stored override use the current catalog default. */
    public BigDecimal getExtraVideoUnitPrice() {
        return extraVideoUnitPrice == null ? DEFAULT_EXTRA_VIDEO_UNIT_PRICE : extraVideoUnitPrice;
    }
}
