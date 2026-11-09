package com.ondemandmonitoring.flightarea.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.flightarea.client.MinistryFlightBanClient;
import com.ondemandmonitoring.flightarea.config.FlightAreaProperties;
import com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse;
import com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse.MinistryFlightBan;
import org.springframework.stereotype.Service;

@Service
public class FlightAreaAssessmentService {
    private final MinistryFlightBanClient client;
    private final FlightAreaProperties properties;

    public FlightAreaAssessmentService(MinistryFlightBanClient client, FlightAreaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public FlightAreaAssessmentResponse assess(String latitudeText, String longitudeText, String radiusText, String ignoredAltitude) {
        double latitude = parse(latitudeText, "latitude");
        double longitude = parse(longitudeText, "longitude");
        double radius = parse(radiusText, "radiusMeters");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                || radius <= 0 || radius > properties.getMaxRadiusM()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid flight-area coordinates or radius");
        }
        MinistryFlightBan result = client.check(latitude, longitude, radius);
        return new FlightAreaAssessmentResponse(
                new FlightAreaAssessmentResponse.Location(latitude, longitude, radius), result);
    }

    private static double parse(String value, String name) {
        try {
            double result = Double.parseDouble(value == null ? "" : value.trim());
            if (!Double.isFinite(result)) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, name + " must be a number");
        }
    }
}
