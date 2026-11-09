package com.ondemandmonitoring.flightarea.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse;
import com.ondemandmonitoring.flightarea.service.FlightAreaAssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flight Area")
@RestController
@RequestMapping("/api/flight-area-assessment")
@RequiredArgsConstructor
public class FlightAreaAssessmentController {
    private final FlightAreaAssessmentService service;

    @Operation(summary = "Check official restricted flight zones")
    @GetMapping
    public ResponseEntity<ApiResponse<FlightAreaAssessmentResponse>> assess(
            @RequestParam String latitude,
            @RequestParam String longitude,
            @RequestParam String radiusMeters,
            @RequestParam(required = false) String requestedAltitudeAgl,
            @RequestParam(defaultValue = "false") boolean includeOsm) {
        return ResponseEntity.ok(ApiResponse.ok("Official restricted-zone check completed",
                service.assess(latitude, longitude, radiusMeters, requestedAltitudeAgl)));
    }
}
