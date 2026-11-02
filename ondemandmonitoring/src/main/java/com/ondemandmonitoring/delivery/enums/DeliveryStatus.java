package com.ondemandmonitoring.delivery.enums;

/**
 * Represents the business lifecycle of an order's result delivery.
 *
 * <p>The status advances independently from mission status so review and access
 * to original media can be enforced explicitly. FINAL_PAYMENT_PENDING and
 * PAYMENT_CONFIRMED remain for legacy delivery records; new Orders settle the
 * invoice before mission execution.</p>
 */
public enum DeliveryStatus {
    PROCESSING,
    READY_FOR_MANAGER_REVIEW,
    CUSTOMER_REVIEW,
    REVISION_REQUESTED,
    /** Legacy status for results accepted before assignment-first payment. */
    FINAL_PAYMENT_PENDING,
    /** Legacy status for payment-confirmed deliveries before direct handoff. */
    PAYMENT_CONFIRMED,
    READY_FOR_DELIVERY,
    DELIVERED
}
