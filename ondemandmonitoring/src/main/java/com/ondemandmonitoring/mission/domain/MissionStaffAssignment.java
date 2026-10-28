package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.user.domain.User;
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
@Table(name = "mission_staff_assignments")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionStaffAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    User staff;

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_role", length = 50)
    MissionStaffRole assignedRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_status", length = 50)
    MissionStaffAssignmentStatus responseStatus;

    @Column(name = "response_at")
    Instant responseAt;

    @Column(name = "decline_reason", length = 500)
    String declineReason;

    @Column(name = "assigned_at")
    Instant assignedAt;

    @Column(name = "responded_at")
    Instant respondedAt;

    @Builder.Default
    @Column(name = "is_current", nullable = false)
    Boolean isCurrent = true;

    @Column(name = "released_at")
    Instant releasedAt;

    @Column(name = "release_reason", length = 500)
    String releaseReason;
}
