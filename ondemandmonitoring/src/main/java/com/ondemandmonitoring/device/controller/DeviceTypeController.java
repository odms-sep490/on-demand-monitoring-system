package com.ondemandmonitoring.device.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.device.dto.request.DeviceTypeCreateRequest;
import com.ondemandmonitoring.device.dto.request.DeviceTypeUpdateRequest;
import com.ondemandmonitoring.device.dto.response.DeviceTypeResponse;
import com.ondemandmonitoring.device.service.IDeviceTypeService;
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

@Tag(name = "Device Types", description = "APIs for managing device types")
@RestController
@RequestMapping("/api/device-types")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasAnyRole('MANAGER','ADMIN','STAFF')")
public class DeviceTypeController {

    IDeviceTypeService deviceTypeService;

    @Operation(summary = "Create device type", description = "Creates a new device type specification")
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<DeviceTypeResponse>> create(@Valid @RequestBody DeviceTypeCreateRequest request) {
        DeviceTypeResponse response = deviceTypeService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created("Device type created successfully", response));
    }

    @Operation(summary = "Get device type by ID", description = "Retrieves details of a device type by its ID")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceTypeResponse>> getById(@PathVariable String id) {
        DeviceTypeResponse response = deviceTypeService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Get all device types", description = "Retrieves a paginated list of device types")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DeviceTypeResponse>>> getAll(
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PageResponse<DeviceTypeResponse> response = deviceTypeService.getAll(pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Update device type", description = "Updates an existing device type by its ID")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<DeviceTypeResponse>> update(
            @PathVariable String id,
            @Valid @RequestBody DeviceTypeUpdateRequest request) {
        DeviceTypeResponse response = deviceTypeService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Device type updated successfully", response));
    }

    @Operation(summary = "Delete device type", description = "Deletes a device type by its ID")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        deviceTypeService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Device type deleted successfully", null));
    }
}
