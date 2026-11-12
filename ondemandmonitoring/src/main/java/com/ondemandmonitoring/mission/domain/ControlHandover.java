package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "control_handovers")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ControlHandover extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_device_assignment_id")
    MissionDeviceAssignment missionDeviceAssignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_assignment_id", nullable = false)
    MissionStaffAssignment staffAssignment;

    @Column(name = "status", nullable = false, length = 50)
    String status; // CONFIRMED / CANCELLED

    @Column(name = "acknowledgement_text", length = 500)
    String acknowledgementText;

    @Column(name = "confirmed_at", nullable = false)
    Instant confirmedAt = Instant.now();
}
