package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_handover_items")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceHandoverItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "handover_id", nullable = false)
    private MaintenanceHandover handover;

    @Column(name = "item_name", nullable = false, length = 255)
    private String itemName;

    @Column(name = "expected_quantity", nullable = false)
    private Integer expectedQuantity;

    @Column(name = "received_quantity")
    private Integer receivedQuantity;

    @Column(name = "condition_note", length = 500)
    private String conditionNote;

    @Column(name = "actual_condition_note", length = 500)
    private String actualConditionNote;

    @Column(name = "discrepancy_note", length = 1000)
    private String discrepancyNote;
}
