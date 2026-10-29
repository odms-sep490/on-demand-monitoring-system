package com.ondemandmonitoring.weather.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckType;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.service.IPersistedPreDeviceCheckService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.weather.domain.MissionWeatherCheck;
import com.ondemandmonitoring.weather.dto.WeatherPreflightCheckRequest;
import com.ondemandmonitoring.weather.dto.WeatherPreflightCheckResponse;
import com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository;
import com.ondemandmonitoring.weather.service.WeatherPreflightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Weather Preflight", description = "Real weather checks before device takeoff")
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherPreflightController {

    private static final double DEFAULT_LATITUDE = 10.6402;
    private static final double DEFAULT_LONGITUDE = 106.6912;

    private final MissionRepository missionRepository;
    private final MissionWeatherCheckRepository weatherCheckRepository;
    private final IPersistedPreDeviceCheckService preDeviceChecks;
    private final WeatherPreflightService weatherPreflightService;

    @Operation(
            summary = "Check current weather before takeoff",
            description = "Loads current Open-Meteo data and returns the mission preflight verdict")
    @PostMapping("/preflight-check")
    public ResponseEntity<ApiResponse<WeatherPreflightCheckResponse>> check(
            @RequestBody(required = false) WeatherPreflightCheckRequest request) {
        double latitude = request != null && request.latitude() != null
                ? request.latitude() : DEFAULT_LATITUDE;
        double longitude = request != null && request.longitude() != null
                ? request.longitude() : DEFAULT_LONGITUDE;
        String missionId = request == null ? null : request.missionId();
        String deviceId = request == null ? null : request.deviceId();

        WeatherPreflightCheckResponse response = weatherPreflightService.checkCurrent(
                missionId, deviceId, latitude, longitude);

        if (missionId != null && !missionId.isBlank()) {
            Mission mission = missionRepository.findById(missionId)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_NOT_FOUND, "Mission not found: " + missionId));
            MissionWeatherCheck saved = toEntity(mission, response);
            response = toResponse(weatherCheckRepository.save(saved));
            persistPreDeviceWeatherResult(missionId, response.status(), response.summary());
        }

        return ResponseEntity.ok(ApiResponse.ok("Current weather preflight checked", response));
    }

    @Operation(summary = "Get latest mission weather preflight check")
    @GetMapping("/pre-device-checks/latest")
    public ResponseEntity<ApiResponse<WeatherPreflightCheckResponse>> latest(
            @RequestParam String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Latest weather preflight check",
                weatherCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                        .map(WeatherPreflightController::toResponse)
                        .orElse(null)));
    }

    private static MissionWeatherCheck toEntity(
            Mission mission,
            WeatherPreflightCheckResponse response) {
        MissionWeatherCheck entity = new MissionWeatherCheck();
        entity.setMission(mission);
        entity.setDeviceId(response.deviceId());
        entity.setStatus(response.status());
        entity.setSafeToFly(response.safeToFly());
        entity.setSummary(response.summary());
        entity.setWindSpeedMps(response.windSpeedMps());
        entity.setWindGustMps(response.windGustMps());
        entity.setPrecipitationMmH(response.precipitationMmH());
        entity.setVisibilityKm(response.visibilityKm());
        entity.setTemperatureC(response.temperatureC());
        entity.setHumidityPercent(response.humidityPercent());
        entity.setAdvisories(String.join("\n", response.advisories()));
        entity.setCheckedAt(response.checkedAt());
        return entity;
    }

    private static WeatherPreflightCheckResponse toResponse(MissionWeatherCheck check) {
        List<String> advisories = check.getAdvisories() == null || check.getAdvisories().isBlank()
                ? List.of()
                : Arrays.asList(check.getAdvisories().split("\\R"));
        return new WeatherPreflightCheckResponse(
                check.getId(),
                check.getMission().getId(),
                check.getDeviceId(),
                check.getStatus(),
                Boolean.TRUE.equals(check.getSafeToFly()),
                check.getSummary(),
                check.getWindSpeedMps(),
                check.getWindGustMps(),
                check.getPrecipitationMmH(),
                check.getVisibilityKm(),
                check.getTemperatureC(),
                check.getHumidityPercent(),
                advisories,
                check.getCheckedAt());
    }

    private void persistPreDeviceWeatherResult(String missionId, String weatherStatus, String summary) {
        PersistedPreDeviceCheckResponse current = preDeviceChecks.current(missionId);
        if (current == null) {
            return;
        }
        boolean blocksFlight = "FAIL".equalsIgnoreCase(weatherStatus);
        preDeviceChecks.recordSystemResult(
                current.getId(),
                PreDeviceCheckType.WEATHER_CONDITIONS.code(),
                blocksFlight ? PreDeviceItemStatus.FAILED : PreDeviceItemStatus.PASSED,
                summary);
    }
}
