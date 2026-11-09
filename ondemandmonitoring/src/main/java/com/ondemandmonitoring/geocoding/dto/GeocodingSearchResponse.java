package com.ondemandmonitoring.geocoding.dto;

public record GeocodingSearchResponse(
        double latitude,
        double longitude,
        String displayName
) {
}
