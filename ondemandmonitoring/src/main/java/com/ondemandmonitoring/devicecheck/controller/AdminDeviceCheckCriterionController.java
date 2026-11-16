package com.ondemandmonitoring.devicecheck.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionReorderRequest;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionRequest;
import com.ondemandmonitoring.devicecheck.dto.response.DeviceCheckCriterionResponse;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import com.ondemandmonitoring.devicecheck.service.IDeviceCheckCriterionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Device Check Criteria", description = "Admin CRUD for preflight / postflight inspection criteria")
@RestController
@RequestMapping("/api/admin/device-check-criteria")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDeviceCheckCriterionController {
    private final IDeviceCheckCriterionAdminService service;

    @Operation(summary = "List criteria of a phase (PRE or POST) in inspection order")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceCheckCriterionResponse>>> list(@RequestParam DeviceCheckPhase phase) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(phase)));
    }

    @Operation(summary = "Get a criterion")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceCheckCriterionResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @Operation(summary = "Create a criterion")
    @PostMapping
    public ResponseEntity<ApiResponse<DeviceCheckCriterionResponse>> create(
            @Valid @RequestBody DeviceCheckCriterionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Device check criterion created", service.create(request)));
    }

    @Operation(summary = "Update a criterion (phase and code are immutable)")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceCheckCriterionResponse>> update(
            @PathVariable String id, @Valid @RequestBody DeviceCheckCriterionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Device check criterion updated", service.update(id, request)));
    }

    @Operation(summary = "Delete a criterion (past inspection runs keep their own copy)")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Device check criterion deleted", null));
    }

    @Operation(summary = "Set the inspection order of all criteria in a phase")
    @PutMapping("/reorder")
    public ResponseEntity<ApiResponse<List<DeviceCheckCriterionResponse>>> reorder(
            @Valid @RequestBody DeviceCheckCriterionReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Device check criteria reordered", service.reorder(request)));
    }
}
