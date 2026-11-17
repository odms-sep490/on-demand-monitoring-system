package com.ondemandmonitoring.devicecheck.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckItemStatus;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckLevel;
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
public class PersistedPostDeviceCheckItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_device_check_id", nullable = false)
    PersistedPostDeviceCheck postDeviceCheck;

    @Column(name = "check_type", nullable = false, length = 50)
    String checkType;

    @Column(name = "check_name", nullable = false, length = 120)
    String checkName;

    @Column(length = 500)
    String description;

    @Column(length = 80)
    String category;

    @Column
    Boolean required;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    DeviceCheckItemStatus status = DeviceCheckItemStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_level", nullable = false, length = 20)
    DeviceCheckLevel checkLevel;

    @Column(length = 1000)
    String message;

    @Column(name = "checked_at")
    Instant checkedAt;
}


