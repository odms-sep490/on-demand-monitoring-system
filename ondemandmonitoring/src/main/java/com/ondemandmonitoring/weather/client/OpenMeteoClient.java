package com.ondemandmonitoring.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.weather.config.WeatherProperties;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin Open-Meteo hourly forecast client. The base URL is configuration only; callers can
 * never supply an arbitrary URL. Uses its own RestTemplate so it can have strict timeouts
 * without changing the shared RestTemplate used elsewhere.
 */
@Component
@Slf4j
public class OpenMeteoClient {

    static final String HOURLY_FIELDS = String.join(",",
            "temperature_2m",
            "relative_humidity_2m",
            "precipitation_probability",
            "precipitation",
            "weather_code",
            "cloud_cover",
            "visibility",
            "wind_speed_10m",
            "wind_gusts_10m");

    /** One local-time hour of forecast. Any numeric field may be null when the provider has no value. */
    public record HourlyPoint(
            String time,
            Double temperatureC,
            Integer relativeHumidityPercent,
            Integer precipitationProbabilityPercent,
            Double precipitationMm,
            Integer weatherCode,
            Integer cloudCoverPercent,
            Double visibilityMeters,
            Double windSpeedKmh,
            Double windGustKmh) {
    }

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public OpenMeteoClient(WeatherProperties properties) {
        this(buildRestTemplate(properties.getOpenMeteo()), properties);
    }

    /** Test seam: lets tests bind a mock server to the RestTemplate. */
    public OpenMeteoClient(RestTemplate restTemplate, WeatherProperties properties) {
        this.restTemplate = restTemplate;
        this.baseUrl = properties.getOpenMeteo().getBaseUrl();
    }

    private static RestTemplate buildRestTemplate(WeatherProperties.OpenMeteo config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    /**
     * Hourly points for one local day at the location ({@code timezone=auto}, so provider times
     * are already in the location's local time).
     *
     * @return empty when the provider reports the date is outside its forecast range
     * @throws ApiException WEATHER_PROVIDER_UNAVAILABLE on timeouts, 5xx or unparsable data
     */
    public Optional<List<HourlyPoint>> fetchDay(double latitude, double longitude, LocalDate date) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/v1/forecast")
                .queryParam("latitude", latitude)
                .queryParam("longitude", longitude)
                .queryParam("hourly", HOURLY_FIELDS)
                .queryParam("timezone", "auto")
                .queryParam("wind_speed_unit", "kmh")
                .queryParam("start_date", date)
                .queryParam("end_date", date)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");

        try {
            String body = restTemplate
                    .exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            return Optional.of(parse(body));
        } catch (HttpClientErrorException exception) {
            // Open-Meteo answers 400 when start/end date is outside the supported range.
            log.info("Open-Meteo rejected forecast request ({}): {}", exception.getStatusCode(),
                    exception.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException exception) {
            log.warn("Open-Meteo forecast request failed: {}", exception.getMessage());
            throw providerUnavailable();
        }
    }

    private List<HourlyPoint> parse(String body) {
        try {
            JsonNode hourly = objectMapper.readTree(body == null ? "{}" : body).path("hourly");
            JsonNode times = hourly.path("time");
            if (!times.isArray()) {
                throw providerUnavailable();
            }
            List<HourlyPoint> points = new ArrayList<>();
            for (int i = 0; i < times.size(); i++) {
                points.add(new HourlyPoint(
                        times.get(i).asText(),
                        number(hourly, "temperature_2m", i),
                        integer(hourly, "relative_humidity_2m", i),
                        integer(hourly, "precipitation_probability", i),
                        number(hourly, "precipitation", i),
                        integer(hourly, "weather_code", i),
                        integer(hourly, "cloud_cover", i),
                        number(hourly, "visibility", i),
                        number(hourly, "wind_speed_10m", i),
                        number(hourly, "wind_gusts_10m", i)));
            }
            return points;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Open-Meteo response could not be parsed: {}", exception.getMessage());
            throw providerUnavailable();
        }
    }

    private static Double number(JsonNode hourly, String field, int index) {
        JsonNode node = hourly.path(field).path(index);
        return node.isNumber() ? node.asDouble() : null;
    }

    private static Integer integer(JsonNode hourly, String field, int index) {
        JsonNode node = hourly.path(field).path(index);
        return node.isNumber() ? (int) Math.round(node.asDouble()) : null;
    }

    private static ApiException providerUnavailable() {
        return new ApiException(ErrorCode.WEATHER_PROVIDER_UNAVAILABLE);
    }
}
