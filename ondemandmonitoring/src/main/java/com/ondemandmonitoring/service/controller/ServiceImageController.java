package com.ondemandmonitoring.service.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import com.ondemandmonitoring.service.service.IServiceImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Service Illustrations")
@RequestMapping("/api/services/{serviceId}/image")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ServiceImageController {
    private final IServiceImageService imageService;

    @Operation(summary = "Upload or replace a service illustration (JPG/PNG/WebP, max 5 MB)")
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ServiceResponse> upload(@PathVariable String serviceId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(imageService.upload(serviceId, file));
    }

    @Operation(summary = "Remove a service illustration")
    @DeleteMapping
    public ApiResponse<ServiceResponse> remove(@PathVariable String serviceId) {
        return ApiResponse.ok(imageService.remove(serviceId));
    }
}
