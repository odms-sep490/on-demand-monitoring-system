package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_part_replacements")
@Getter
@Setter
@NoArgsConstructor
public class MaintenancePartReplacement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber = 1;

    @Column(name = "part_name", nullable = false, length = 255)
    private String partName;

    @Column(name = "part_number", length = 120)
    private String partNumber;

    @Column(name = "old_serial", length = 120)
    private String oldSerial;

    @Column(name = "new_serial", length = 120)
    private String newSerial;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 40)
    private String unit = "pcs";

    @Column(name = "replacement_reason", nullable = false, length = 1000)
    private String replacementReason;

    @Column(name = "removed_part_condition", length = 500)
    private String removedPartCondition;

    @Column(name = "cost_amount", precision = 15, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "currency", length = 16)
    private String currency;

    @Column(name = "evidence_refs_json", columnDefinition = "text")
    private String evidenceRefsJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id")
    private User recordedBy;
}
