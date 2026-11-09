package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.response.MissionPermissionsResponse;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/missions")
@RequiredArgsConstructor
public class MissionAccessController {
    private final IMissionAuthorizationService authorization;

    @GetMapping("/{id}/permissions")
    @PreAuthorize("@missionAuthorizationService.canViewMission(#id)")
    public ApiResponse<MissionPermissionsResponse> permissions(@PathVariable String id) {
        return ApiResponse.ok(new MissionPermissionsResponse(
                authorization.canRespondToMission(id),
                authorization.canStartMission(id),
                authorization.canCancelMission(id),
                authorization.canControlFlight(id),
                authorization.canOperatePayload(id),
                authorization.canInspectDevice(id),
                authorization.canMaintainDevice(id),
                authorization.canUploadMissionMedia(id),
                authorization.canCompleteMission(id),
                authorization.canSubmitMissionResult(id),
                authorization.canExecuteMonitoringChecklist(id),
                authorization.canAttachChecklistEvidence(id),
                authorization.canDetachChecklistEvidence(id),
                authorization.canAssessMonitoringChecklist(id),
                authorization.canConfirmMissionFailure(id)));
    }
}
