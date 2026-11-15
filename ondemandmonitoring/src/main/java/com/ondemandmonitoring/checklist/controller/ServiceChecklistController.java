package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.checklist.dto.request.*;
import com.ondemandmonitoring.checklist.dto.response.ServiceChecklistResponse;
import com.ondemandmonitoring.checklist.service.IServiceChecklistService;
import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/services/{serviceId}/checklists")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ServiceChecklistController {
    private final IServiceChecklistService service;

    @GetMapping
    public ApiResponse<List<ServiceChecklistResponse>> getAll(@PathVariable String serviceId) {
        return ApiResponse.ok(service.getByService(serviceId, false));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ServiceChecklistResponse>> assign(
            @PathVariable String serviceId, @Valid @RequestBody ChecklistAssignmentRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.created("Checklist assigned", service.assign(serviceId, request)));
    }

    @PatchMapping("/{checklistId}")
    public ApiResponse<ServiceChecklistResponse> updateOrder(@PathVariable String serviceId,
            @PathVariable String checklistId, @Valid @RequestBody ChecklistOrderRequest request) {
        return ApiResponse.ok(service.updateOrder(serviceId, checklistId, request.getDisplayOrder()));
    }

    @PutMapping("/{checklistId}/requirements")
    public ApiResponse<ServiceChecklistResponse> updateRequirements(@PathVariable String serviceId,
            @PathVariable String checklistId, @Valid @RequestBody ServiceChecklistRequirementsRequest request) {
        return ApiResponse.ok(service.updateRequirements(serviceId, checklistId, request));
    }

    @PostMapping("/{checklistId}/fork")
    public ResponseEntity<ApiResponse<ServiceChecklistResponse>> fork(
            @PathVariable String serviceId, @PathVariable String checklistId) {
        return ResponseEntity.status(201).body(ApiResponse.created("Checklist copied for this service",
                service.fork(serviceId, checklistId)));
    }

    @PutMapping("/order")
    public ApiResponse<List<ServiceChecklistResponse>> reorder(@PathVariable String serviceId,
            @Valid @RequestBody ChecklistReorderRequest request) {
        return ApiResponse.ok(service.reorder(serviceId, request));
    }

    @DeleteMapping("/{checklistId}")
    public ApiResponse<Void> unassign(@PathVariable String serviceId, @PathVariable String checklistId) {
        service.unassign(serviceId, checklistId);
        return ApiResponse.ok("Checklist unassigned", null);
    }
}
