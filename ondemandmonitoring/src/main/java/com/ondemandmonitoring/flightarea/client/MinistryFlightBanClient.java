package com.ondemandmonitoring.flightarea.client;

import com.ondemandmonitoring.flightarea.config.FlightAreaProperties;
import com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse.MinistryFlightBan;
import com.ondemandmonitoring.flightarea.support.ProviderRestTemplates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Slf4j
public class MinistryFlightBanClient {

    public static final String SOURCE_URL = "https://cambay.mod.gov.vn/";
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private final RestTemplate restTemplate;
    private final FlightAreaProperties properties;

    @Autowired
    public MinistryFlightBanClient(FlightAreaProperties properties) {
        this(ProviderRestTemplates.create(
                properties.getMinistryFlightBan().getConnectTimeoutMs(),
                properties.getMinistryFlightBan().getReadTimeoutMs()), properties);
    }

    MinistryFlightBanClient(RestTemplate restTemplate, FlightAreaProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public MinistryFlightBan check(double latitude, double longitude, double radiusMeters) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("feature", feature(latitude, longitude, radiusMeters));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    properties.getMinistryFlightBan().getBaseUrl() + properties.getMinistryFlightBan().getPath(),
                    new HttpEntity<>(body, headers), Map.class);
            if (response == null) return unavailable("EMPTY_RESPONSE");
            return new MinistryFlightBan(
                    true,
                    number(response.get("totalArea")),
                    number(response.get("violatedArea")),
                    number(response.get("safeArea")),
                    strings(response.get("violatedZones")),
                    SOURCE_URL,
                    null);
        } catch (RuntimeException exception) {
            log.warn("Ministry flight-ban lookup failed: {}", exception.toString());
            return unavailable("PROVIDER_UNAVAILABLE");
        }
    }

    private MinistryFlightBan unavailable(String errorCode) {
        return new MinistryFlightBan(false, null, null, null, List.of(), SOURCE_URL, errorCode);
    }

    private Map<String, Object> feature(double latitude, double longitude, double radiusMeters) {
        int count = Math.max(16, properties.getMinistryFlightBan().getPolygonPoints());
        List<List<Double>> ring = new ArrayList<>(count + 1);
        double angularDistance = radiusMeters / EARTH_RADIUS_M;
        double latitudeRad = Math.toRadians(latitude);
        double longitudeRad = Math.toRadians(longitude);
        for (int index = 0; index < count; index++) {
            double bearing = 2 * Math.PI * index / count;
            double pointLat = Math.asin(Math.sin(latitudeRad) * Math.cos(angularDistance)
                    + Math.cos(latitudeRad) * Math.sin(angularDistance) * Math.cos(bearing));
            double pointLon = longitudeRad + Math.atan2(
                    Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latitudeRad),
                    Math.cos(angularDistance) - Math.sin(latitudeRad) * Math.sin(pointLat));
            ring.add(List.of(Math.toDegrees(pointLon), Math.toDegrees(pointLat)));
        }
        ring.add(ring.getFirst());
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Polygon");
        geometry.put("coordinates", List.of(ring));
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("properties", Map.of());
        feature.put("geometry", geometry);
        return feature;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
