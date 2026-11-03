package com.ondemandmonitoring.mission.enums;

/** Business lifecycle of a Mission. Device-operation progress belongs to MissionDeviceAssignment. */
public enum MissionStatus {
    PLANNING,

    /** Planning is finalized; operational work is blocked until payment is verified. */
    PENDING_PAYMENT,

    /** Payment was verified; resource checks and crew confirmation are in progress. */
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
