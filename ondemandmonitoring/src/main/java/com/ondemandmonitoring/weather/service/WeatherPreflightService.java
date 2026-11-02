package com.ondemandmonitoring.weather.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.weather.dto.WeatherForecastResponse;
import com.ondemandmonitoring.weather.dto.WeatherForecastStatus;
import com.ondemandmonitoring.weather.dto.WeatherPreflightCheckResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WeatherPreflightService {

    private static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final double MAX_WIND_MPS = 8.0;
    private static final double MAX_GUST_MPS = 11.0;
    private static final double MAX_PRECIPITATION_MM = 1.0;
    private static final double MIN_VISIBILITY_KM = 5.0;

    private final WeatherForecastService forecastService;
    private final Clock clock;

    @Autowired
    public WeatherPreflightService(WeatherForecastService forecastService) {
        this(forecastService, Clock.systemUTC());
    }

    WeatherPreflightService(WeatherForecastService forecastService, Clock clock) {
        this.forecastService = forecastService;
        this.clock = clock;
    }

    public WeatherPreflightCheckResponse checkCurrent(
            String missionId,
            String deviceId,
            double latitude,
            double longitude) {
        validateCoordinates(latitude, longitude);
        ZonedDateTime localNow = ZonedDateTime.now(clock).withZoneSameInstant(OPERATION_ZONE);
        WeatherForecastResponse forecast = forecastService.forecast(
                Double.toString(latitude),
                Double.toString(longitude),
                localNow.toLocalDate().toString(),
                String.format("%02d:00", localNow.getHour()));

        if (forecast.status() != WeatherForecastStatus.AVAILABLE
                || forecast.windSpeedKmh() == null
                || forecast.windGustKmh() == null
                || forecast.precipitationMm() == null
                || forecast.visibilityMeters() == null
                || forecast.temperatureC() == null
                || forecast.relativeHumidityPercent() == null) {
            throw new ApiException(ErrorCode.WEATHER_PROVIDER_UNAVAILABLE);
        }

        double windSpeedMps = round1(forecast.windSpeedKmh() / 3.6);
        double windGustMps = round1(forecast.windGustKmh() / 3.6);
        double precipitationMm = round1(forecast.precipitationMm());
        double visibilityKm = round1(forecast.visibilityMeters() / 1000.0);
        List<String> advisories = advisories(windSpeedMps, windGustMps, precipitationMm, visibilityKm);
        boolean safeToFly = windSpeedMps <= MAX_WIND_MPS
                && windGustMps <= MAX_GUST_MPS
                && precipitationMm <= MAX_PRECIPITATION_MM
                && visibilityKm >= MIN_VISIBILITY_KM;

        return new WeatherPreflightCheckResponse(
                null,
                missionId,
                deviceId,
                safeToFly ? "PASS" : "WARN",
                safeToFly,
                safeToFly ? "Thời tiết hiện tại phù hợp để cất cánh."
                        : "Thời tiết hiện tại cần chú ý trước khi cất cánh.",
                windSpeedMps,
                windGustMps,
                precipitationMm,
                visibilityKm,
                forecast.temperatureC(),
                forecast.relativeHumidityPercent(),
                advisories,
                Instant.now(clock),
                false,
                null,
                null);
    }

    private static List<String> advisories(
            double windSpeedMps,
            double windGustMps,
            double precipitationMm,
            double visibilityKm) {
        List<String> values = new ArrayList<>();
        if (windSpeedMps > MAX_WIND_MPS || windGustMps > MAX_GUST_MPS) {
            values.add("Gió mạnh, cần hoãn cất cánh hoặc giảm trần bay.");
        }
        if (precipitationMm > MAX_PRECIPITATION_MM) {
            values.add("Có mưa, cần kiểm tra chống nước payload/camera.");
        }
        if (visibilityKm < MIN_VISIBILITY_KM) {
            values.add("Tầm nhìn thấp, không đủ điều kiện bay an toàn.");
        }
        if (values.isEmpty()) {
            values.add("Dữ liệu Open-Meteo hiện tại đáp ứng ngưỡng kiểm tra preflight.");
        }
        return List.copyOf(values);
    }

    private static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Latitude or longitude is invalid");
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
