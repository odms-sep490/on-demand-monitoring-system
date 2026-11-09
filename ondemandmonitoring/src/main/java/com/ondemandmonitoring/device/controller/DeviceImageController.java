package com.ondemandmonitoring.device.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import com.ondemandmonitoring.device.service.IDeviceImageService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/devices/{deviceId}/image")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class DeviceImageController {
    private final IDeviceImageService imageService;

    @Operation(summary = "Upload or replace a device image (JPG/PNG/WebP, max 5 MB)")
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DeviceResponse> upload(
            @PathVariable String deviceId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(imageService.upload(deviceId, file));
    }

    @Operation(summary = "Remove a device image")
    @DeleteMapping
    public ApiResponse<DeviceResponse> remove(@PathVariable String deviceId) {
        return ApiResponse.ok(imageService.remove(deviceId));
    }
}
