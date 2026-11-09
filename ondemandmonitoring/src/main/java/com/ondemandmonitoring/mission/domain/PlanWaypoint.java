package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@Entity
@Table(
        name = "plan_waypoints",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_plan_waypoints_plan_sequence",
                columnNames = {"mission_plan_id", "sequence"}),
        indexes = @Index(name = "idx_plan_waypoints_mission_plan", columnList = "mission_plan_id"))
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PlanWaypoint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "mission_plan_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_plan_waypoints_mission_plan"))
    MissionPlan missionPlan;

    @Column(name = "sequence", nullable = false)
    Integer sequence;

    @Column(name = "sim_x", nullable = false)
    Double simX;

    @Column(name = "sim_y", nullable = false)
    Double simY;

    /** Optional authoritative WGS84 coordinates used for real-world airspace validation. */
    @Column(name = "longitude")
    Double longitude;

    @Column(name = "latitude")
    Double latitude;

    /*
     * Planned Gazebo World Z in meters for the simulation planner.
     * This is not PX4 relative altitude, terrain-relative altitude, or NED Down.
     */
    @Column(name = "altitude_m", nullable = false)
    Double altitudeM;

    @Column(name = "planned_speed_mps")
    Double plannedSpeedMps;

}
