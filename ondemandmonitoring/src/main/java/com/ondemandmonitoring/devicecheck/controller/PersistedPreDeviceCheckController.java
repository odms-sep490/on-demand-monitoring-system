package com.ondemandmonitoring.devicecheck.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.devicecheck.dto.request.PreDeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.service.IPersistedPreDeviceCheckService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PersistedPreDeviceCheckController {

    private final IPersistedPreDeviceCheckService service;

    @PostMapping("/api/missions/{missionId}/pre-device-checks")
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public ResponseEntity<ApiResponse<PersistedPreDeviceCheckResponse>> start(
            @PathVariable String missionId) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created(service.start(missionId)));
    }

    @GetMapping("/api/missions/{missionId}/pre-device-checks")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public ApiResponse<List<PersistedPreDeviceCheckResponse>> history(
            @PathVariable String missionId) {
        return ApiResponse.ok(service.history(missionId));
    }

    @GetMapping("/api/missions/{missionId}/pre-device-checks/current")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public ApiResponse<PersistedPreDeviceCheckResponse> current(
            @PathVariable String missionId) {
        return ApiResponse.ok(service.current(missionId));
    }

    @GetMapping("/api/pre-device-checks/{id}")
    @PreAuthorize("@deviceCheckAuthorizationService.canViewPreCheck(#id)")
    public ApiResponse<PersistedPreDeviceCheckResponse> get(
            @PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PatchMapping("/api/pre-device-checks/{id}/items/{checkType}")
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPreCheck(#id)")
    public ApiResponse<PersistedPreDeviceCheckResponse> update(
            @PathVariable String id,
            @PathVariable String checkType,
            @Valid @RequestBody PreDeviceCheckItemUpdateRequest request) {
        return ApiResponse.ok(service.update(id, checkType, request));
    }

    @PostMapping("/api/pre-device-checks/{id}/items/{checkType}/recheck")
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPreCheck(#id)")
    public ApiResponse<PersistedPreDeviceCheckResponse> recheck(
            @PathVariable String id,
            @PathVariable String checkType) {
        return ApiResponse.ok(service.recheck(id, checkType));
    }
}

