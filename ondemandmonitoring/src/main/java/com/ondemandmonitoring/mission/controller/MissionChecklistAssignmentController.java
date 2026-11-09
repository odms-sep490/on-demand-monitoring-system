package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentSelectionResponse;
import com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions/{missionId}/checklist-assignments")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class MissionChecklistAssignmentController {
    private final IMissionChecklistAssignmentService service;
    @GetMapping
    public ApiResponse<ChecklistAssignmentSelectionResponse> get(@PathVariable String missionId) { return ApiResponse.ok(service.get(missionId)); }
    @PutMapping
    public ApiResponse<ChecklistAssignmentSelectionResponse> replace(@PathVariable String missionId,
            @Valid @RequestBody ChecklistAssignmentSelectionRequest request) { return ApiResponse.ok(service.replace(missionId, request)); }
}
