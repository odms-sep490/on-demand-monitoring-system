package com.ondemandmonitoring.devicecheck.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Admin-managed pre/post device inspection criterion. Runs copy these fields into their own items when started,
 * so editing or deleting a criterion never rewrites inspection history.
 */
@Entity
@Table(name = "device_check_criteria",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_check_criteria_phase_code", columnNames = {"phase", "code"}))
@Getter
@Setter
public class DeviceCheckCriterion extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeviceCheckPhase phase;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 80)
    private String category;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;
}
