package com.ondemandmonitoring.flightarea.dto;

import java.util.List;

/** Result of the official restricted-area lookup. */
public record FlightAreaAssessmentResponse(Location location, MinistryFlightBan ministryFlightBan) {
    public record Location(double latitude, double longitude, double radiusMeters) { }

    public record MinistryFlightBan(
            boolean available,
            Double totalAreaSquareMeters,
            Double violatedAreaSquareMeters,
            Double safeAreaSquareMeters,
            List<String> violatedZones,
            String sourceUrl,
            String errorCode) { }
}
