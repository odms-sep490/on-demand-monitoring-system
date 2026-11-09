package com.ondemandmonitoring.weather.dto;

public record WeatherPreflightCheckRequest(
        String missionId,
        String deviceId,
        Double latitude,
        Double longitude) {
}
