package com.ondemandmonitoring.mission.enums;

public enum MissionStatus {

    PLANNING,

    /** Financial gate: operational work is blocked until the required deposit is verified. */
    WAITING_DEPOSIT,

    WAITING_OPERATOR_ACCEPTANCE,

    /** Mission planning was confirmed; resource preparation and crew response are in progress. */
    READY,

    /** Required crew members accepted and the mission is scheduled for execution. */
    SCHEDULED,

    /** Device powered on, telemetry link with GCS app confirmed. */
    CONNECTED,

    /** Operator opened checklist; digital preflight validation in progress. */
    PREFLIGHT_CHECKING,

    /**
     * All preflight checks passed; flight-access token issued.
     * Operator confirms take-off on the web dashboard.
     */
    READY_TO_FLY,

    /**
     * Preflight failed; operator notified.
     * Device will be routed to maintenance or charge station and order re-queued.
     */
    FAILED_PREFLIGHT,

    /** Device airborne, transmitting realtime GPS + video stream. */
    IN_FLIGHT,

    /** Legacy alias kept for compatibility - maps to IN_FLIGHT in most contexts. */
    IN_PROGRESS,

    RETURNING,

    POSTFLIGHT_CHECKING,

    /** Technical postflight finished; awaiting Inspector operational acceptance. */
    PENDING_REVIEW,

    /** Operationally accepted, not business-final (MissionResult approval completes the Order). */
    COMPLETED,

    FAILED,

    CANCELLED
}
