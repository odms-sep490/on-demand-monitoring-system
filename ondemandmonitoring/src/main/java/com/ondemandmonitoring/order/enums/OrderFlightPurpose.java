package com.ondemandmonitoring.order.enums;

/**
 * Purpose of the flight, in the classes of Decree 288/2025/ND-CP (Article 4.3) that this
 * service actually sells. It is the "Muc dich bay" printed on the flight-permit application,
 * and is not the same thing as {@link OrderUsagePurpose} (what the customer does with the data).
 *
 * <p>Recreation, flight training, official duty and research are deliberately absent: the
 * platform sells none of them.
 */
public enum OrderFlightPurpose {
    /** Surveying, mapping and aerial monitoring: the core of the service catalogue. */
    SURVEY_MONITORING,
    /** Agricultural production support (crop monitoring). */
    AGRICULTURE,
    /** Construction progress and site monitoring. */
    CONSTRUCTION_MONITORING,
    /** Factory, roof and industrial-facility inspection. */
    INDUSTRIAL_INSPECTION,
    /** General monitoring of a defined area. */
    AREA_MONITORING,
    /** Forest condition and forest-resource monitoring. */
    FOREST_MONITORING,
    OTHER;

    public static final OrderFlightPurpose DEFAULT = SURVEY_MONITORING;
}
