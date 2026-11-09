package com.ondemandmonitoring.order.enums;

/** What the customer wants to happen when weather prevents the planned flight. */
public enum OrderWeatherFallback {
    /** Move the flight to the next suitable day inside the preferred range. */
    AUTO_RESCHEDULE,
    /** Contact the customer before changing anything. */
    CONTACT_CUSTOMER,
    /** Cancel the order. */
    CANCEL_ORDER
}
