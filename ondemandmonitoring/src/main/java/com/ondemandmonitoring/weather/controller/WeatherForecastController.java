package com.ondemandmonitoring.weather.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.weather.dto.WeatherForecastResponse;
import com.ondemandmonitoring.weather.service.WeatherForecastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Weather Forecast", description = "Advisory weather preview for customers creating a request")
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherForecastController {

    private final WeatherForecastService forecastService;

    @Operation(
            summary = "Forecast for a location and local time",
            description = "Advisory only. Parameters are plain strings so malformed values return 400, not 500.")
    @GetMapping("/forecast")
    public ApiResponse<WeatherForecastResponse> forecast(
            @RequestParam String latitude,
            @RequestParam String longitude,
            @RequestParam String date,
            @RequestParam String time) {
        return ApiResponse.ok(forecastService.forecast(latitude, longitude, date, time));
    }
}
