package com.ondemandmonitoring.order.enums;

/** Tracking lifecycle of one order checklist item against its (single) owning Mission. */
public enum ChecklistAssignmentStatus {
    /** Not assigned to any effective Mission. */
    UNASSIGNED,
    /** Assigned to exactly one effective Mission, execution not started yet. */
    ASSIGNED,
    /** Owning Mission has started executing (IN_FLIGHT). */
    IN_PROGRESS,
    /** Owning Mission has been operationally completed. */
    COMPLETED
}
