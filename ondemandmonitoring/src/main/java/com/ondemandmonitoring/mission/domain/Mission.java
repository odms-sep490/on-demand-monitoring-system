package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.mission.enums.MissionRescheduleReason;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.order.domain.Order;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Setter
@org.hibernate.annotations.Check(name="ck_mission_internal_correction_origin", constraints="inspection_correction_id is null or (mission_purpose = 'REWORK' and result_revision_case_id is null)")
@Entity
@Table(name = "missions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mission_id_order", columnNames = {"id", "order_id"})},
        indexes = @Index(name = "ix_missions_status_schedule", columnList = "status,scheduled_start_at"))
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Mission extends BaseEntity {

    @Column(name = "mission_code", nullable = false, unique = true, length = 50)
    String missionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    MissionStatus status;

    @OneToOne(mappedBy = "mission", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    FlightPermit flightPermit;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_reschedule_reason", length = 50)
    MissionRescheduleReason lastRescheduleReason;

    @Column(name = "last_reschedule_note", length = 1000)
    String lastRescheduleNote;

    @Column(name = "last_rescheduled_at")
    Instant lastRescheduledAt;

    @Column(name = "schedule_revision", nullable = false)
    Long scheduleRevision = 0L;

    // ===== Relationship to Order =====
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    Order order;

    @Column(name = "scope_label", length = 255)
    String scopeLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "mission_purpose", nullable = false, length = 20)
    MissionPurpose purpose = MissionPurpose.ORIGINAL;

    /** Internal pre-publication remediation; never a customer revision case. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspection_correction_id")
    com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection inspectionCorrection;

    @Column(name = "result_revision_case_id", length = 255)
    String resultRevisionCaseId;

    @Column(name = "rework_request_id", unique = true)
    java.util.UUID reworkRequestId;

    @Column(name = "rework_request_fingerprint", length = 64)
    String reworkRequestFingerprint;


    @Column(name = "replaces_mission_id", length = 255)
    String replacesMissionId;

    @Column(name = "cancellation_at")
    Instant cancellationAt;

    @Column(name = "cancelled_by", length = 255)
    String cancelledBy;

    @Column(name = "cancellation_reason", length = 1000)
    String cancellationReason;

    // ===== Schedule =====

    @Column(name = "scheduled_start_at", nullable = false)
    Instant scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false)
    Instant scheduledEndAt;

    @Column(name = "actual_start_at")
    Instant actualStartAt;

    @Column(name = "actual_end_at")
    Instant actualEndAt;

    @Column(name = "failure_requested_at")
    Instant failureRequestedAt;

    @Column(name = "failure_reason", length = 1000)
    String failureReason;

    @Column(name = "completed_at")
    Instant completedAt;

}
