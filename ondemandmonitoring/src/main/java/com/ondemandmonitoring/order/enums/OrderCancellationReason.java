package com.ondemandmonitoring.order.enums;

/** Stable machine-readable reason for a terminal order cancellation. */
public enum OrderCancellationReason {
    QUOTATION_EXPIRED,
    DEPOSIT_PAYMENT_EXPIRED,
    CONTRACT_REVIEW_REQUIRED,
    CONTRACT_SYSTEM_RESPONSIBILITY,
    CONTRACT_CUSTOMER_RESPONSIBILITY,
    PERMIT_REJECTED
}
