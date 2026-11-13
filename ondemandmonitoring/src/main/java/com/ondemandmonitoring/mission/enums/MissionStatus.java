package com.ondemandmonitoring.mission.enums;

/** Business lifecycle of a Mission. Device-operation progress belongs to MissionDeviceAssignment. */
public enum MissionStatus {
    PLANNING,

    /** The plan is finalized; staff confirmation and drone checks are in progress. */
    PENDING_CONFIRMATION,

    /** Every required crew member and device resource is ready. */
    READY,

    IN_PROGRESS,
    /** The inspector submitted the accepted result and it is waiting for Manager approval. */
    AWAITING_REVIEW,
    /** Legacy status retained for data compatibility. */
    PENDING_REVIEW,
    COMPLETED,
    FAILED,
    CANCELLED
}
