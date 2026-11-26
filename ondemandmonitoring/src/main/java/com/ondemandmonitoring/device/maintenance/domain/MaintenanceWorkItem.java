package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkActionType;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkStatus;
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
@Table(name = "maintenance_work_items")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceWorkItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber = 1;

    @Column(name = "component_area", nullable = false, length = 200)
    private String componentArea;

    @Column(name = "symptom", length = 1000)
    private String symptom;

    @Column(name = "diagnosis", length = 1000)
    private String diagnosis;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private MaintenanceWorkActionType actionType;

    @Column(name = "work_performed", length = 2000)
    private String workPerformed;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_id")
    private User performedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MaintenanceWorkStatus status = MaintenanceWorkStatus.PENDING;

    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    @Column(name = "evidence_refs_json", columnDefinition = "text")
    private String evidenceRefsJson;
}
