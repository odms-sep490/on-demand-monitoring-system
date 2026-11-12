package com.ondemandmonitoring.delivery.enums;

/**
 * Represents the business lifecycle of an order's result delivery.
 *
 * <p>The status advances independently from mission status so review and access
 * to original media can be enforced explicitly. Legacy payment states remain
 * readable for existing records during migration.</p>
 */
public enum DeliveryStatus {
    PROCESSING,
    READY_FOR_MANAGER_REVIEW,
    CUSTOMER_REVIEW,
    REVISION_REQUESTED,
    /** Customer accepted the result; Deposit or agreed Mission allocation is not ready yet. */
    AWAITING_MISSION_PAYMENT,
    /** Legacy status retained to read records written by the retired final-payment flow. */
    FINAL_PAYMENT_PENDING,
    /** Legacy status for payment-confirmed deliveries before direct handoff. */
    PAYMENT_CONFIRMED,
    READY_FOR_DELIVERY,
    DELIVERED
}
