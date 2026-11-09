package com.ondemandmonitoring.finance.enums;

public enum PaymentStatus {
    PENDING, PROCESSING, SUCCESS, FAILED, CANCELLED, REFUNDED,
    /** Provider reported success after commercial cancellation; manual refund/reconciliation is required. */
    REQUIRES_REVIEW
}
