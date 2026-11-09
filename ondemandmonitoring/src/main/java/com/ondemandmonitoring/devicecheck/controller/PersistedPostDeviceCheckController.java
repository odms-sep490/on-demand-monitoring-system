package com.ondemandmonitoring.devicecheck.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPostDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.service.IPersistedPostDeviceCheckService;
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
public class PersistedPostDeviceCheckController {

    private final IPersistedPostDeviceCheckService service;

    @PostMapping("/api/missions/{missionId}/post-device-checks")
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#missionId)")
    public ResponseEntity<ApiResponse<PersistedPostDeviceCheckResponse>> start(
            @PathVariable String missionId) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created(service.start(missionId)));
    }

    @GetMapping("/api/missions/{missionId}/post-device-checks")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public ApiResponse<List<PersistedPostDeviceCheckResponse>> history(
            @PathVariable String missionId) {
        return ApiResponse.ok(service.history(missionId));
    }

    @GetMapping("/api/missions/{missionId}/post-device-checks/current")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public ApiResponse<PersistedPostDeviceCheckResponse> current(
            @PathVariable String missionId) {
        return ApiResponse.ok(service.current(missionId));
    }

    @GetMapping("/api/post-device-checks/{id}")
    @PreAuthorize("@deviceCheckAuthorizationService.canViewPostCheck(#id)")
    public ApiResponse<PersistedPostDeviceCheckResponse> get(
            @PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PatchMapping("/api/post-device-checks/{id}/items/{checkType}")
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPostCheck(#id)")
    public ApiResponse<PersistedPostDeviceCheckResponse> update(
            @PathVariable String id,
            @PathVariable String checkType,
            @Valid @RequestBody DeviceCheckItemUpdateRequest request) {
        return ApiResponse.ok(service.update(id, checkType, request));
    }
}


