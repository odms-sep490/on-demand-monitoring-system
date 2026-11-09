package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.enums.LockStatus;
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
@Table(name = "resource_time_locks")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ResourceTimeLock extends BaseEntity {

    @Column(name = "resource_id", nullable = false, length = 100)
    String resourceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    Mission mission;

    @Column(name = "start_time", nullable = false)
    Instant startTime;

    @Column(name = "end_time", nullable = false)
    Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_status", nullable = false, length = 50)
    LockStatus lockStatus;

    @Column(name = "expires_at")
    Instant expiresAt;

    @Column(name = "resource_type", length = 20)
    String resourceType;

    @Column(name = "released_at")
    Instant releasedAt;

    @Column(name = "release_reason", length = 500)
    String releaseReason;

    @Column(name = "reserved_by", length = 255)
    String reservedBy;
    @Column(name = "released_by", length = 255)
    String releasedBy;
}
