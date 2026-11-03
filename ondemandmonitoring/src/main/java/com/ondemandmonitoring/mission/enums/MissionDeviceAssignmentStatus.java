package com.ondemandmonitoring.mission.enums;

/** Runtime lifecycle of one device assigned to a mission. */
public enum MissionDeviceAssignmentStatus {
    ASSIGNED,
    CONNECTED,
    CONNECTION_LOST,
    PREFLIGHT_CHECKING,
    READY_TO_FLY,
    IN_PROGRESS,
    RETURNING,
    POSTFLIGHT_CHECKING,
    COMPLETED,
    FAILED,
    RELEASED
}
