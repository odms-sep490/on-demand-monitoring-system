package com.ondemandmonitoring.review.enums;

/** What the system suggests; the manager still makes the decision. */
public enum ReadinessRecommendation {
    /** Required manual criteria are still unanswered. */
    COMPLETE_REVIEW,
    /** A blocking criterion failed: ask the customer for more, or reject. */
    REQUEST_INFO_OR_REJECT,
    APPROVE_WITH_NOTES,
    APPROVE
}
