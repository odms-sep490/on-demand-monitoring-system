package com.ondemandmonitoring.order.enums;

/** Commercial lifecycle of one order checklist item. */
public enum ChecklistPricingStatus {
    AUTO_PRICED,
    NEEDS_MANAGER_REVIEW,
    APPROVED,
    ADJUSTED_AND_APPROVED,
    DUPLICATE,
    REJECTED,
    BLOCKED_BY_POLICY
}
