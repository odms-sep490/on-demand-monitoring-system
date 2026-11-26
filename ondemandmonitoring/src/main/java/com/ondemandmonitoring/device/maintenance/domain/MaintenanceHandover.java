package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_handovers")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceHandover extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private HandoverDirection direction;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber = 1;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private HandoverStatus status = HandoverStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    /** Null while an inbound handover waits in the shared Technical queue for someone to claim it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id")
    private User toUser;

    @Column(name = "maintenance_reason", length = 2000)
    private String maintenanceReason;

    @Column(name = "overall_condition", length = 1000)
    private String overallCondition;

    @Column(name = "existing_damage_notes", length = 2000)
    private String existingDamageNotes;

    @Column(name = "unfinished_work_notes", length = 2000)
    private String unfinishedWorkNotes;

    @Column(name = "actual_handover_at")
    private Instant actualHandoverAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_id")
    private User confirmedBy;

    @Column(name = "discrepancy_notes", length = 2000)
    private String discrepancyNotes;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Column(name = "report_revision")
    private Integer reportRevision;

    @Column(name = "evidence_refs_json", columnDefinition = "text")
    private String evidenceRefsJson;

    @Column(name = "receipt_evidence_refs_json", columnDefinition = "text")
    private String receiptEvidenceRefsJson;

    @Column(name = "idempotency_key", unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "locked", nullable = false)
    private boolean locked = false;

    @OneToMany(mappedBy = "handover", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaintenanceHandoverItem> items = new ArrayList<>();
}
