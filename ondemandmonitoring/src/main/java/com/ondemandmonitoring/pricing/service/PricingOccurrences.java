package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;

/** How many times an order is carried out: one for a single visit, the chosen number for a repeating schedule. */
public final class PricingOccurrences {
    /** Upper bound so a bad value can never inflate a quote without limit. */
    public static final int MAX = 100;

    private PricingOccurrences() {}

    public static int of(Order order) {
        if (order == null || order.getRecurrenceType() == null
                || order.getRecurrenceType() == OrderRecurrenceType.NONE) return 1;
        Integer occurrences = order.getRecurrenceOccurrences();
        return occurrences == null ? 1 : Math.max(1, Math.min(MAX, occurrences));
    }
}
