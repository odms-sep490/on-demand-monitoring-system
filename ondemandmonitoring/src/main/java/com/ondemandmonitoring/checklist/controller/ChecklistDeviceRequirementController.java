package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.checklist.dto.response.ChecklistDeviceRequirementResponse;
import com.ondemandmonitoring.checklist.service.IChecklistDeviceRequirementService;
import com.ondemandmonitoring.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/checklists/{checklistId}/device-types")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ChecklistDeviceRequirementController {
    private final IChecklistDeviceRequirementService service;

    @PostMapping("/{deviceTypeId}")
    public ResponseEntity<ApiResponse<ChecklistDeviceRequirementResponse>> add(
            @PathVariable String checklistId, @PathVariable String deviceTypeId) {
        return ResponseEntity.status(201)
                .body(ApiResponse.created("Device type added to checklist", service.add(checklistId, deviceTypeId)));
    }

    @DeleteMapping("/{deviceTypeId}")
    public ResponseEntity<Void> remove(
            @PathVariable String checklistId, @PathVariable String deviceTypeId) {
        service.remove(checklistId, deviceTypeId);
        return ResponseEntity.noContent().build();
    }
}
