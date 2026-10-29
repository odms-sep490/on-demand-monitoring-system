package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.enums.MissionRescheduleReason;
import com.ondemandmonitoring.order.domain.Order;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Setter
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

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_prepare_status", nullable = false, length = 30)
    MissionResourcePrepareStatus resourcePrepareStatus = MissionResourcePrepareStatus.PLANNING;

    @Enumerated(EnumType.STRING)
    @Column(name = "flight_permit_status", nullable = false, length = 30)
    FlightPermitStatus flightPermitStatus = FlightPermitStatus.NOT_REQUESTED;

    @Column(name = "flight_permit_note", length = 1000)
    String flightPermitNote;

    @Column(name = "flight_permit_file_name", length = 160)
    String flightPermitFileName;

    @Column(name = "flight_permit_content_type", length = 100)
    String flightPermitContentType;

    @Column(name = "flight_permit_data_url", columnDefinition = "TEXT")
    String flightPermitDataUrl;

    @Column(name = "flight_permit_resource_hold_until")
    Instant flightPermitResourceHoldUntil;

    @Column(name = "flight_permit_resources_released_at")
    Instant flightPermitResourcesReleasedAt;

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


    @Column(name = "replaces_mission_id", length = 255)
    String replacesMissionId;

    @Column(name = "cancellation_at")
    Instant cancellationAt;

    @Column(name = "cancelled_by", length = 255)
    String cancelledBy;

    @Column(name = "cancellation_reason", length = 1000)
    String cancellationReason;

    @Column(name = "safe_stop_status", length = 40)
    @Enumerated(EnumType.STRING)
    com.ondemandmonitoring.mission.enums.MissionSafeStopStatus safeStopStatus;
    @Column(name = "safe_stop_requested_at")
    Instant safeStopRequestedAt;
    @Column(name = "safe_stop_requested_by", length = 255)
    String safeStopRequestedBy;
    @Column(name = "safe_stop_reason", length = 1000)
    String safeStopReason;
    @Column(name = "safe_stop_requires_landing")
    Boolean safeStopRequiresLanding;
    @Column(name = "safe_stop_confirmed_at")
    Instant safeStopConfirmedAt;
    @Column(name = "safe_stop_confirmed_by", length = 255)
    String safeStopConfirmedBy;
    @Column(name = "safe_stop_confirmation", length = 1000)
    String safeStopConfirmation;
    @Column(name = "safe_stop_cleaned_at")
    Instant safeStopCleanedAt;
    @Column(name = "safe_stop_failure_reason", length = 1000)
    String safeStopFailureReason;

    // ===== Schedule =====

    @Column(name = "scheduled_start_at", nullable = false)
    Instant scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false)
    Instant scheduledEndAt;

    @Column(name = "actual_start_at")
    Instant actualStartAt;

    @Column(name = "actual_end_at")
    Instant actualEndAt;

    @Column(name = "completed_at")
    Instant completedAt;

}
