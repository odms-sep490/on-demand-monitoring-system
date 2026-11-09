package com.ondemandmonitoring.devicecheck.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.devicecheck.dto.request.MediaProbeRequest;
import com.ondemandmonitoring.devicecheck.dto.response.MediaProbeResponse;
import com.ondemandmonitoring.devicecheck.service.IMediaProbeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PreDeviceMediaProbeController {

    private final IMediaProbeService mediaProbeService;

    @PostMapping(path = "/api/internal/v1/pre-device-checks/{runId}/media-probe",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPreCheck(#runId)")
    public ApiResponse<MediaProbeResponse> verify(
            @PathVariable String runId,
            @Valid @ModelAttribute MediaProbeRequest request) {
        return ApiResponse.ok(mediaProbeService.verify(runId, request));
    }

    @PostMapping("/api/pre-device-checks/{runId}/media-probe/automatic")
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPreCheck(#runId)")
    public ApiResponse<MediaProbeResponse> verifyAutomatic(@PathVariable String runId) {
        return ApiResponse.ok(mediaProbeService.verifyAutomatic(runId));
    }
}
