package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_workflow_events")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceWorkflowEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @Column(name = "device_id", nullable = false, length = 255)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 40)
    private MaintenanceWorkflowStage fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", length = 40)
    private MaintenanceWorkflowStage toStage;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber = 1;

    @Column(name = "revision")
    private Integer revision;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "evidence_refs_json", columnDefinition = "text")
    private String evidenceRefsJson;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
