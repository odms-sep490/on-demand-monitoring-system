package com.ondemandmonitoring.weather.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Clean ODMS forecast DTO (never the raw Open-Meteo payload).
 * {@code warnings} holds stable codes (RAIN, WIND, GUST, LOW_VISIBILITY, THUNDERSTORM)
 * so the UI can localise them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherForecastResponse(
        WeatherForecastStatus status,
        double latitude,
        double longitude,
        String forecastTime,
        Double temperatureC,
        Integer relativeHumidityPercent,
        Integer precipitationProbabilityPercent,
        Double precipitationMm,
        Double windSpeedKmh,
        Double windGustKmh,
        Integer cloudCoverPercent,
        Double visibilityMeters,
        Integer weatherCode,
        String weatherLabel,
        WeatherSuitability suitability,
        List<String> warnings) {

    public static WeatherForecastResponse notAvailable(double latitude, double longitude, String forecastTime) {
        return new WeatherForecastResponse(
                WeatherForecastStatus.FORECAST_NOT_AVAILABLE, latitude, longitude, forecastTime,
                null, null, null, null, null, null, null, null, null, null, null, List.of());
    }
}
