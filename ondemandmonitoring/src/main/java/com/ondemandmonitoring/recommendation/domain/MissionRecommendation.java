package com.ondemandmonitoring.recommendation.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Historical, non-reserving snapshot of one date's availability recommendation. */
@Entity
@Table(name = "mission_recommendations", indexes = {
        @Index(name = "ix_mission_recommendation_mission_created", columnList = "mission_id,created_at"),
        @Index(name = "ix_mission_recommendation_mission_date", columnList = "mission_id,recommendation_date")
})
@Getter
@Setter
@NoArgsConstructor
public class MissionRecommendation extends BaseEntity {
    @Column(name = "mission_id", nullable = false, length = 255)
    private String missionId;

    @Column(name = "execution_plan_revision", nullable = false)
    private long executionPlanRevision;

    @Column(name = "schedule_revision", nullable = false)
    private long scheduleRevision;

    @Column(name = "search_from", nullable = false)
    private LocalDate searchFrom;

    @Column(name = "search_to", nullable = false)
    private LocalDate searchTo;

    @Column(name = "recommendation_date", nullable = false)
    private LocalDate recommendationDate;

    @Column(name = "scheduled_start_at", nullable = false)
    private Instant scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false)
    private Instant scheduledEndAt;

    @Column(name = "available_device_count", nullable = false)
    private int availableDeviceCount;

    @Column(name = "capability_compatible_device_count", nullable = false)
    private int capabilityCompatibleDeviceCount;

    @Column(name = "available_staff_count", nullable = false)
    private int availableStaffCount;

    @Column(name = "recommended_device_count", nullable = false)
    private int recommendedDeviceCount;

    @Column(name = "device_shortage_count", nullable = false)
    private int deviceShortageCount;

    @Column(name = "staff_shortage_count", nullable = false)
    private int staffShortageCount;

    @Column(name = "device_availability_ratio", precision = 8, scale = 4, nullable = false)
    private BigDecimal deviceAvailabilityRatio;

    @Column(name = "staff_availability_ratio", precision = 8, scale = 4, nullable = false)
    private BigDecimal staffAvailabilityRatio;

    @Column(name = "effective_availability_ratio", precision = 8, scale = 4, nullable = false)
    private BigDecimal effectiveAvailabilityRatio;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RecommendationRisk riskLevel;

    @Column(name = "required_capabilities_json", nullable = false, columnDefinition = "text")
    private String requiredCapabilitiesJson;

    @Column(name = "device_candidates_json", nullable = false, columnDefinition = "text")
    private String deviceCandidatesJson;

    @Column(name = "warnings_json", nullable = false, columnDefinition = "text")
    private String warningsJson;

    @Column(name = "ai_model", nullable = false, length = 255)
    private String aiModel;

    @Column(name = "ai_minimum_devices", nullable = false)
    private int aiMinimumDevices;

    @Column(name = "ai_recommended_devices", nullable = false)
    private int aiRecommendedDevices;

    @Column(name = "ai_reason", columnDefinition = "text")
    private String aiReason;

    @Column(name = "ai_assumptions_json", nullable = false, columnDefinition = "text")
    private String aiAssumptionsJson;

    @Column(name = "ai_warnings_json", nullable = false, columnDefinition = "text")
    private String aiWarningsJson;
}
