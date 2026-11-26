package com.ondemandmonitoring.device.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

/**
 * Maintenance work order for a device.
 * <p>
 * Relationships:
 * - 1 Device → many MaintenanceTickets (one device can accumulate fault history)
 * - 1 User (staff) → many MaintenanceTickets (one staff member can be assigned to many tickets)
 */
@Getter
@Setter
@Entity
@Table(name = "maintenance_tickets")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MaintenanceTicket extends BaseEntity {

    @Column(name = "ticket_code", nullable = false, unique = true, length = 100)
    String ticketCode;

    /**
     * The physical device that requires maintenance.
     * Many tickets can belong to one device over its lifetime.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    Device device; // field name is 'device' to be device-type agnostic

    /**
     * The staff user assigned to resolve this ticket.
     * One staff user can be assigned to many tickets.
     * Nullable — ticket may be OPEN and unassigned initially.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_staff_id")
    User assignedStaff;

    @Column(name = "reported_by", length = 100)
    String reportedBy; // staff id or "AUTOMATED_PREFLIGHT_GATE"

    @Column(name = "issue_type", nullable = false, length = 100)
    String issueType; // PREFLIGHT_HARDWARE_FAIL / POSTFLIGHT_DAMAGE / ROUTINE

    @Column(name = "severity", nullable = false, length = 50)
    String severity; // LOW / MEDIUM / HIGH / CRITICAL

    @Column(name = "description", length = 2000)
    String description;

    @Column(name = "status", nullable = false, length = 50)
    String status = "OPEN"; // OPEN / IN_PROGRESS / RESOLVED / CLOSED

    @Column(name = "resolution_notes", length = 2000)
    String resolutionNotes; // filled when status → RESOLVED or CLOSED

    @Column(name = "opened_at", nullable = false)
    Instant openedAt = Instant.now();

    @Column(name = "resolved_at")
    Instant resolvedAt;

    @Column(name = "closed_at")
    Instant closedAt; // set when ticket is formally closed after verification

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_stage", length = 40)
    MaintenanceWorkflowStage workflowStage;

    @Column(name = "current_round", nullable = false)
    Integer currentRound = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handed_over_by_id")
    User handedOverBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_manager_id")
    User receivingManager;

    @Column(name = "legacy_record", nullable = false)
    Boolean legacyRecord = Boolean.FALSE;

    @Column(name = "waiting_reason", length = 1000)
    String waitingReason;

    @Column(name = "waiting_parts_needed", length = 2000)
    String waitingPartsNeeded;

    @Column(name = "waiting_expected_at")
    Instant waitingExpectedAt;

    @Column(name = "accepted_report_revision")
    Integer acceptedReportRevision;

    @Column(name = "workflow_updated_at")
    Instant workflowUpdatedAt;
}

