package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "mission_device_assignments")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionDeviceAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    Device device;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "device_role", nullable = false, length = 50)
    DeviceRole deviceRole = DeviceRole.MAIN;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "checkup_status", nullable = false, length = 50)
    MissionDeviceAssignmentStatus checkupStatus = MissionDeviceAssignmentStatus.PENDING;

    @Column(name = "postcheck_status", length = 50)
    String postcheckStatus;

    @Column(name = "verified_by", length = 100)
    String verifiedBy;

    @Column(name = "verified_at")
    Instant verifiedAt;

    @Column(name = "failure_notes", columnDefinition = "TEXT")
    String failureNotes;

    @Column(name = "status", length = 50)
    String status;

    @Builder.Default
    @Column(name = "is_current", nullable = false)
    Boolean isCurrent = true;

    @Column(name = "assigned_at")
    Instant assignedAt;

    @Column(name = "released_at")
    Instant releasedAt;

    @Column(name = "release_reason", length = 500)
    String releaseReason;

}
