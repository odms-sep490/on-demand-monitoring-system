package com.ondemandmonitoring.geocoding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.geocoding.dto.GeocodingSearchResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class GeocodingService {

    private static final Pattern LEADING_ADDRESS_NUMBER = Pattern.compile("^\\s*(\\d+)");

    RestTemplate restTemplate;
    ObjectMapper objectMapper = new ObjectMapper();

    public GeocodingSearchResponse search(String query) {
        if (query == null || query.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Address query is required");
        }

        String normalizedQuery = query.strip();

        try {
            return searchWithNominatim(normalizedQuery);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return searchWithArcGis(normalizedQuery);
            }
            throw exception;
        } catch (RestClientException exception) {
            log.warn("Nominatim geocoding failed, falling back to ArcGIS: {}", exception.getMessage());
            return searchWithArcGis(normalizedQuery);
        }
    }

    public GeocodingSearchResponse reverse(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "GPS coordinates are invalid");
        }

        String url = UriComponentsBuilder
                .fromUriString("https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/reverseGeocode")
                .queryParam("location", longitude + "," + latitude)
                .queryParam("f", "json")
                .queryParam("langCode", "vi")
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        headers.set(HttpHeaders.USER_AGENT, "OnDemandMonitor/1.0 (local development)");

        try {
            String body = restTemplate
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode location = root.path("location");
            if (location.isMissingNode() || root.path("error").isObject()) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy địa chỉ tại vị trí này.");
            }

            double resultLatitude = location.path("y").asDouble(latitude);
            double resultLongitude = location.path("x").asDouble(longitude);
            String displayName = reverseDisplayName(root, latitude, longitude);
            return new GeocodingSearchResponse(resultLatitude, resultLongitude, displayName);
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("ArcGIS reverse geocoding failed: {}", exception.getMessage());
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể kết nối dịch vụ tìm địa chỉ.");
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể đọc kết quả tìm địa chỉ.");
        }
    }

    private GeocodingSearchResponse searchWithNominatim(String query) {
        String url = UriComponentsBuilder
                .fromUriString("https://nominatim.openstreetmap.org/search")
                .queryParam("q", query)
                .queryParam("format", "jsonv2")
                .queryParam("limit", "1")
                .queryParam("countrycodes", "vn")
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        headers.set(HttpHeaders.USER_AGENT, "OnDemandMonitor/1.0 (local development)");

        try {
            String body = restTemplate
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            JsonNode results = objectMapper.readTree(body == null ? "[]" : body);
            requireResult(results);

            JsonNode first = results.get(0);
            double latitude = Double.parseDouble(first.path("lat").asText());
            double longitude = Double.parseDouble(first.path("lon").asText());
            String displayName = first.path("display_name").asText(query);
            return new GeocodingSearchResponse(latitude, longitude, displayName);
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể đọc kết quả tìm địa chỉ.");
        }
    }

    private GeocodingSearchResponse searchWithArcGis(String query) {
        String url = UriComponentsBuilder
                .fromUriString("https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates")
                .queryParam("SingleLine", query)
                .queryParam("f", "json")
                .queryParam("maxLocations", "5")
                .queryParam("sourceCountry", "VNM")
                .queryParam("outFields", "Match_addr,Addr_type,Score")
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        headers.set(HttpHeaders.USER_AGENT, "OnDemandMonitor/1.0 (local development)");

        try {
            String body = restTemplate
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            JsonNode candidates = objectMapper.readTree(body == null ? "{}" : body).path("candidates");
            requireResult(candidates);

            JsonNode best = chooseArcGisCandidate(query, candidates);
            JsonNode location = best.path("location");
            double latitude = location.path("y").asDouble();
            double longitude = location.path("x").asDouble();
            String displayName = best.path("address").asText(query);
            return new GeocodingSearchResponse(latitude, longitude, displayName);
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("ArcGIS geocoding failed: {}", exception.getMessage());
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể kết nối dịch vụ tìm địa chỉ.");
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể đọc kết quả tìm địa chỉ.");
        }
    }

    private JsonNode chooseArcGisCandidate(String query, JsonNode candidates) {
        String requestedNumber = leadingAddressNumber(query);
        JsonNode fallback = candidates.get(0);
        JsonNode bestPoint = null;
        JsonNode bestNumberMatch = null;

        for (JsonNode candidate : candidates) {
            String address = candidate.path("address").asText("");
            String type = candidate.path("attributes").path("Addr_type").asText("");
            boolean pointAddress = "PointAddress".equalsIgnoreCase(type);
            boolean numberMatches = requestedNumber != null
                    && address.matches("(?i).*\\b" + Pattern.quote(requestedNumber) + "\\b.*");

            if (pointAddress && numberMatches) {
                return candidate;
            }
            if (numberMatches && bestNumberMatch == null) {
                bestNumberMatch = candidate;
            }
            if (pointAddress && bestPoint == null) {
                bestPoint = candidate;
            }
        }

        if (bestNumberMatch != null) {
            return bestNumberMatch;
        }
        if (bestPoint != null) {
            return bestPoint;
        }
        return fallback;
    }

    private String leadingAddressNumber(String query) {
        Matcher matcher = LEADING_ADDRESS_NUMBER.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String reverseDisplayName(JsonNode root, double latitude, double longitude) {
        JsonNode address = root.path("address");
        String matchAddress = address.path("Match_addr").asText("");
        if (!matchAddress.isBlank()) {
            return matchAddress;
        }
        String longLabel = address.path("LongLabel").asText("");
        if (!longLabel.isBlank()) {
            return longLabel;
        }
        String shortLabel = address.path("ShortLabel").asText("");
        if (!shortLabel.isBlank()) {
            return shortLabel;
        }
        return latitude + ", " + longitude;
    }

    private void requireResult(JsonNode results) {
        if (!results.isArray() || results.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy địa chỉ này.");
        }
    }
}
