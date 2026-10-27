package com.ondemandmonitoring.mission.enums;

/** Operational reason for moving a mission before take-off. */
public enum MissionRescheduleReason {
    WEATHER_UNSAFE,
    FLIGHT_PERMISSION_DELAY,
    RESOURCE_UNAVAILABLE,
    EQUIPMENT_ISSUE,
    SITE_ACCESS_BLOCKED,
    CUSTOMER_REQUEST,
    OTHER
}
