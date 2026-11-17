package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions/{missionId}/evidence-submissions")
@PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN') and @missionAuthorizationService.canViewMission(#missionId)")
public class MissionEvidenceSubmissionController {
    private final MissionEvidenceSubmissionService service;
    @GetMapping
    public ApiResponse<MissionEvidenceSubmissionService.State> get(@PathVariable String missionId) {
        return ApiResponse.ok(service.get(missionId));
    }
    @PostMapping
    @PreAuthorize("hasRole('STAFF') and @missionAuthorizationService.canViewMission(#missionId)")
    public ApiResponse<MissionEvidenceSubmissionService.State> submit(@PathVariable String missionId,
            @Valid @RequestBody MissionEvidenceSubmissionService.Command command) {
        return ApiResponse.ok(service.submit(missionId, command));
    }
}
