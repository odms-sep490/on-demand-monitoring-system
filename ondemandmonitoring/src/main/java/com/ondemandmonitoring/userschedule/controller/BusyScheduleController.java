package com.ondemandmonitoring.userschedule.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.userschedule.dto.request.BusyScheduleRequest;
import com.ondemandmonitoring.userschedule.dto.response.BusyScheduleResponse;
import com.ondemandmonitoring.userschedule.dto.response.WeeklyBusyScheduleResponse;
import com.ondemandmonitoring.userschedule.service.IBusyScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

@Tag(name = "Operator Busy Schedules", description = "Drone operators register up to 4 busy sessions per week (Mon-Sun)")
@RestController
@RequestMapping("/api/busy-schedules")
@RequiredArgsConstructor
public class BusyScheduleController {
    private final IBusyScheduleService service;

    @Operation(summary = "List my busy sessions for the week containing the given date (default: current week)")
    @GetMapping("/me")
    @PreAuthorize("hasRole('STAFF')")
    public ResponseEntity<ApiResponse<WeeklyBusyScheduleResponse>> myWeek(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(service.getMyWeek(date)));
    }

    @Operation(summary = "List an operator's busy sessions for a week (manager/admin)")
    @GetMapping("/users/{staffId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<WeeklyBusyScheduleResponse>> staffWeek(
            @PathVariable String staffId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(service.getStaffWeek(staffId, date)));
    }

    @Operation(summary = "Get a busy session")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<BusyScheduleResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @Operation(summary = "Register a busy session")
    @PostMapping
    @PreAuthorize("hasRole('STAFF')")
    public ResponseEntity<ApiResponse<BusyScheduleResponse>> create(@Valid @RequestBody BusyScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Busy session registered", service.create(request)));
    }

    @Operation(summary = "Change a busy session")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('STAFF')")
    public ResponseEntity<ApiResponse<BusyScheduleResponse>> update(
            @PathVariable String id, @Valid @RequestBody BusyScheduleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Busy session updated", service.update(id, request)));
    }

    @Operation(summary = "Cancel a busy session")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('STAFF')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Busy session cancelled", null));
    }
}
