package com.ondemandmonitoring.checklist.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.DeviceType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A device type associated with a checklist definition. */
@Entity
@Table(name = "checklist_device_requirements",
        uniqueConstraints = @UniqueConstraint(name = "uk_checklist_device_requirement",
                columnNames = {"checklist_definition_id", "device_type_id"}),
        indexes = {
            @Index(name = "ix_checklist_device_requirement_checklist", columnList = "checklist_definition_id"),
            @Index(name = "ix_checklist_device_requirement_type", columnList = "device_type_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ChecklistDeviceRequirement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_definition_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_checklist_device_requirement_checklist"))
    private ChecklistDefinition checklistDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_type_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_checklist_device_requirement_type"))
    private DeviceType deviceType;
}
