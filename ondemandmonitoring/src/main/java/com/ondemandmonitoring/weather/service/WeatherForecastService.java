package com.ondemandmonitoring.weather.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.weather.client.OpenMeteoClient;
import com.ondemandmonitoring.weather.client.OpenMeteoClient.HourlyPoint;
import com.ondemandmonitoring.weather.config.WeatherProperties;
import com.ondemandmonitoring.weather.dto.WeatherForecastResponse;
import com.ondemandmonitoring.weather.dto.WeatherForecastStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Customer weather preview for the selected location and time. Purely informational:
 * it never touches orders and never blocks the customer.
 */
@Service
public class WeatherForecastService {

    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH':00'");

    private record CacheKey(String latitude, String longitude, LocalDate date, int hour) {
    }

    private record CacheEntry(HourlyPoint point, Instant expiresAt) {
    }

    private final OpenMeteoClient client;
    private final CustomerWeatherSuitabilityEvaluator evaluator;
    private final WeatherProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public WeatherForecastService(
            OpenMeteoClient client,
            CustomerWeatherSuitabilityEvaluator evaluator,
            WeatherProperties properties) {
        this(client, evaluator, properties, Clock.systemUTC());
    }

    public WeatherForecastService(
            OpenMeteoClient client,
            CustomerWeatherSuitabilityEvaluator evaluator,
            WeatherProperties properties,
            Clock clock) {
        this.client = client;
        this.evaluator = evaluator;
        this.properties = properties;
        this.clock = clock;
    }

    public WeatherForecastResponse forecast(String latitudeText, String longitudeText, String dateText, String timeText) {
        double latitude = parseCoordinate(latitudeText, "latitude", 90);
        double longitude = parseCoordinate(longitudeText, "longitude", 180);
        LocalDate date = parseDate(dateText);
        LocalTime time = parseTime(timeText);

        LocalDate utcToday = LocalDate.now(clock);
        // One day of slack so a location ahead of UTC can still ask about its own "today".
        if (date.isBefore(utcToday.minusDays(1))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Date must not be in the past");
        }

        String forecastTime = date.atTime(time.getHour(), 0).format(HOUR_KEY);
        int maxDays = properties.getOpenMeteo().getMaxForecastDays();
        if (date.isAfter(utcToday.plusDays(maxDays))) {
            return WeatherForecastResponse.notAvailable(latitude, longitude, forecastTime);
        }

        Optional<HourlyPoint> point = findPoint(latitude, longitude, date, time.getHour(), forecastTime);
        if (point.isEmpty() || point.get().temperatureC() == null) {
            return WeatherForecastResponse.notAvailable(latitude, longitude, forecastTime);
        }
        return toResponse(latitude, longitude, point.get());
    }

    private Optional<HourlyPoint> findPoint(double latitude, double longitude, LocalDate date, int hour, String forecastTime) {
        CacheKey key = new CacheKey(round2(latitude), round2(longitude), date, hour);
        Instant now = clock.instant();
        if (properties.getCache().isEnabled()) {
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return Optional.of(cached.point());
            }
        }

        Optional<List<HourlyPoint>> day = client.fetchDay(latitude, longitude, date);
        Optional<HourlyPoint> match = day.flatMap(points -> points.stream()
                .filter(p -> forecastTime.equals(p.time()))
                .findFirst());

        if (match.isPresent() && properties.getCache().isEnabled()) {
            if (cache.size() >= properties.getCache().getMaxEntries()) {
                cache.clear();
            }
            cache.put(key, new CacheEntry(match.get(), now.plusSeconds(properties.getCache().getTtlMinutes() * 60L)));
        }
        return match;
    }

    private WeatherForecastResponse toResponse(double latitude, double longitude, HourlyPoint p) {
        Double rain = p.precipitationProbabilityPercent() == null ? null : p.precipitationProbabilityPercent().doubleValue();
        CustomerWeatherSuitabilityEvaluator.Result result = evaluator.evaluate(
                rain, p.windSpeedKmh(), p.windGustKmh(), p.visibilityMeters(), p.weatherCode());
        return new WeatherForecastResponse(
                WeatherForecastStatus.AVAILABLE,
                latitude,
                longitude,
                p.time(),
                p.temperatureC(),
                p.relativeHumidityPercent(),
                p.precipitationProbabilityPercent(),
                p.precipitationMm(),
                p.windSpeedKmh(),
                p.windGustKmh(),
                p.cloudCoverPercent(),
                p.visibilityMeters(),
                p.weatherCode(),
                WeatherCodeLabels.label(p.weatherCode()),
                result.suitability(),
                result.warnings());
    }

    private static String round2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static double parseCoordinate(String text, String name, double limit) {
        double value;
        try {
            value = Double.parseDouble(text == null ? "" : text.trim());
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, name + " is invalid");
        }
        if (!Double.isFinite(value) || Math.abs(value) > limit) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, name + " is out of range");
        }
        return value;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text == null ? "" : text.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "date must be yyyy-MM-dd");
        }
    }

    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text == null ? "" : text.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "time must be HH:mm");
        }
    }
}
