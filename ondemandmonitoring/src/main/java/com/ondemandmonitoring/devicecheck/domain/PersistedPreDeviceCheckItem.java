package com.ondemandmonitoring.devicecheck.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckLevel;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.enums.PreflightCheckSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class PersistedPreDeviceCheckItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pre_device_run_id", nullable = false)
    PersistedPreDeviceCheck preDeviceCheck;

    @Column(name = "check_type", nullable = false, length = 50)
    String checkType;

    @Column(name = "check_name", nullable = false, length = 120)
    String checkName;

    @Column(nullable = false, length = 500)
    String description;

    @Column(nullable = false, length = 80)
    String category;

    @Column(nullable = false)
    Boolean required = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    PreflightCheckSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    PreDeviceItemStatus status = PreDeviceItemStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_level", nullable = false, length = 20)
    PreDeviceCheckLevel checkLevel;

    @Column(length = 1000)
    String message;

    @Column(name = "measured_value", length = 1000)
    String measuredValue;

    @Column(name = "error_code", length = 80)
    String errorCode;

    @Column(name = "checked_by", length = 255)
    String checkedBy;

    @Column(name = "checked_at")
    Instant checkedAt;

    @Column(name = "expires_at")
    Instant expiresAt;
}
