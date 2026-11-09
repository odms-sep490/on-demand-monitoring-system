package com.ondemandmonitoring.pricing.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.pricing.enums.TerrainType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "custom_work_assessments", uniqueConstraints = @UniqueConstraint(name = "uk_custom_assessment_order", columnNames = "order_id"))
@Getter @Setter
public class CustomWorkAssessment extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false, updatable = false) private Order order;
    @Column(name = "estimated_flight_hours", precision = 10, scale = 2) private BigDecimal estimatedFlightHours;
    @Column(name = "estimated_distance_km", precision = 12, scale = 2) private BigDecimal estimatedDistanceKm;
    @Column(name = "estimated_area_ha", precision = 14, scale = 3) private BigDecimal estimatedAreaHa;
    @Column(name = "pilot_count") private Integer pilotCount;
    @Column(name = "drone_count") private Integer droneCount;
    @Column(name = "battery_units") private Integer batteryUnits;
    @Enumerated(EnumType.STRING) @Column(length = 30) private TerrainType terrain = TerrainType.FLAT;
    @Column(name = "thermal_required", nullable = false) private boolean thermalRequired;
    @Column(name = "multispectral_required", nullable = false) private boolean multispectralRequired;
    @Column(name = "night_operation", nullable = false) private boolean nightOperation;
    @Column(name = "special_equipment", length = 500) private String specialEquipment;
    @Column(name = "other_cost_drivers", length = 1000) private String otherCostDrivers;
    @Column(name = "assessment_complete", nullable = false) private boolean assessmentComplete;
}
