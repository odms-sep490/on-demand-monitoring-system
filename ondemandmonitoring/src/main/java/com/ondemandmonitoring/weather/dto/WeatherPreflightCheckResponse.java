package com.ondemandmonitoring.weather.dto;

import java.time.Instant;
import java.util.List;

public record WeatherPreflightCheckResponse(
        String id,
        String missionId,
        String deviceId,
        String status,
        boolean safeToFly,
        String summary,
        double windSpeedMps,
        double windGustMps,
        double precipitationMmH,
        double visibilityKm,
        double temperatureC,
        int humidityPercent,
        List<String> advisories,
        Instant checkedAt,
        boolean inspectorAccepted,
        String inspectorAcceptedBy,
        Instant inspectorAcceptedAt) {
}
