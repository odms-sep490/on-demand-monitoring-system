package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.enums.AcceptanceDecisionType;
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
@Table(name = "maintenance_acceptance_decisions")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceAcceptanceDecision extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_handover_id", nullable = false)
    private MaintenanceHandover returnHandover;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_version_id", nullable = false)
    private MaintenanceReportVersion reportVersion;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber;

    @Column(name = "report_revision", nullable = false)
    private Integer reportRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    private AcceptanceDecisionType decision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_by_id", nullable = false)
    private User decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "device_match_confirmed", nullable = false)
    private boolean deviceMatchConfirmed = false;

    @Column(name = "accessories_match_confirmed", nullable = false)
    private boolean accessoriesMatchConfirmed = false;

    @Column(name = "verification_notes", length = 2000)
    private String verificationNotes;

    @Column(name = "rework_reason", length = 2000)
    private String reworkReason;

    @Column(name = "failed_work_item_ids_json", columnDefinition = "text")
    private String failedWorkItemIdsJson;

    @Column(name = "checkpoint_created", nullable = false)
    private boolean checkpointCreated = false;

    @Column(name = "device_unlocked", nullable = false)
    private boolean deviceUnlocked = false;

    @Column(name = "remaining_blockers_json", columnDefinition = "text")
    private String remainingBlockersJson;

    @Column(name = "idempotency_key", unique = true, length = 200)
    private String idempotencyKey;
}
