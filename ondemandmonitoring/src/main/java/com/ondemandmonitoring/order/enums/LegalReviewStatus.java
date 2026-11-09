package com.ondemandmonitoring.order.enums;

/** Legal readiness tracked by ODMS; permit processing itself happens outside the system. */
public enum LegalReviewStatus {
    NOT_REVIEWED,
    NOT_REQUIRED,
    PENDING_PERMIT,
    APPROVED,
    REJECTED
}
