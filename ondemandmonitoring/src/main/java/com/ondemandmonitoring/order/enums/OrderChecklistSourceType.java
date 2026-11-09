package com.ondemandmonitoring.order.enums;

public enum OrderChecklistSourceType {
    /** Automatically copied from the selected service package; never needs Manager classification. */
    SERVICE_TEMPLATE,
    /** Existing catalog checklist selected by the customer outside the package; Manager review is required. */
    CUSTOMER_SELECTED,
    /** Free-text work requested by the customer; Manager review/assessment is required. */
    CUSTOMER_CUSTOM
}
