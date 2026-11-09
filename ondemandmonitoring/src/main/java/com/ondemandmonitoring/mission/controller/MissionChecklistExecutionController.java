package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionChecklistResponse;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/missions/{missionId}/checklist-executions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
public class MissionChecklistExecutionController {
    private final IMissionChecklistExecutionService service;

    @GetMapping
    public ApiResponse<MissionChecklistResponse> get(@PathVariable String missionId) {
        return ApiResponse.ok(service.getByMissionId(missionId));
    }

    @PatchMapping("/{executionId}")
    public ApiResponse<ChecklistExecutionResponse> update(@PathVariable String missionId,
            @PathVariable String executionId, @Valid @RequestBody ChecklistExecutionUpdateRequest request) {
        return ApiResponse.ok(service.update(missionId, executionId, request));
    }
}
