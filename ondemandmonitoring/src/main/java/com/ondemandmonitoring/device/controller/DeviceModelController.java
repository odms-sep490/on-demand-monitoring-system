package com.ondemandmonitoring.device.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.device.dto.request.DeviceModelCreateRequest;
import com.ondemandmonitoring.device.dto.request.DeviceModelUpdateRequest;
import com.ondemandmonitoring.device.dto.response.DeviceModelResponse;
import com.ondemandmonitoring.device.service.IDeviceModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Device Models", description = "APIs for managing device models")
@RestController
@RequestMapping("/api/device-models")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','STAFF')")
public class DeviceModelController {

    IDeviceModelService deviceModelService;

    @Operation(summary = "Create device model", description = "Creates a new device model specification with device types and JSON specs metadata")
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<DeviceModelResponse>> create(@Valid @RequestBody DeviceModelCreateRequest request) {
        DeviceModelResponse response = deviceModelService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created("Device model created successfully", response));
    }

    @Operation(summary = "Get device model by ID", description = "Retrieves details of a device model by its ID")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceModelResponse>> getById(@PathVariable String id) {
        DeviceModelResponse response = deviceModelService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Get all device models", description = "Retrieves a paginated list of device models")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DeviceModelResponse>>> getAll(
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PageResponse<DeviceModelResponse> response = deviceModelService.getAll(pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Update device model", description = "Updates an existing device model by its ID")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<DeviceModelResponse>> update(
            @PathVariable String id,
            @Valid @RequestBody DeviceModelUpdateRequest request) {
        DeviceModelResponse response = deviceModelService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Device model updated successfully", response));
    }

    @Operation(summary = "Delete device model", description = "Deletes a device model by its ID")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        deviceModelService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Device model deleted successfully", null));
    }
}
