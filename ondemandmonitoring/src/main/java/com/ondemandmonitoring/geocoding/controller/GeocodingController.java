package com.ondemandmonitoring.geocoding.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.geocoding.dto.GeocodingSearchResponse;
import com.ondemandmonitoring.geocoding.service.GeocodingService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocoding")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GeocodingController {

    GeocodingService geocodingService;

    @GetMapping("/search")
    public ApiResponse<GeocodingSearchResponse> search(@RequestParam String q) {
        return ApiResponse.ok(geocodingService.search(q));
    }

    @GetMapping("/reverse")
    public ApiResponse<GeocodingSearchResponse> reverse(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        return ApiResponse.ok(geocodingService.reverse(latitude, longitude));
    }
}
