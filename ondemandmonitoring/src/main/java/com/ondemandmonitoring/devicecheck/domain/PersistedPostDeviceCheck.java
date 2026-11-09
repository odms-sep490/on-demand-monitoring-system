package com.ondemandmonitoring.devicecheck.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckStatus;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.Mission;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class PersistedPostDeviceCheck extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_connection_id", nullable = false)
    DeviceConnection deviceConnection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    DeviceCheckStatus status = DeviceCheckStatus.CHECKING;

    @Column(name = "total_checks", nullable = false)
    Integer totalChecks;

    @Column(name = "passed_checks", nullable = false)
    Integer passedChecks = 0;

    @Column(name = "failed_checks", nullable = false)
    Integer failedChecks = 0;

    @Column(name = "started_at", nullable = false)
    Instant startedAt;

    @Column(name = "completed_at")
    Instant completedAt;

    @OneToMany(mappedBy = "postDeviceCheck", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    List<PersistedPostDeviceCheckItem> items = new ArrayList<>();
}
