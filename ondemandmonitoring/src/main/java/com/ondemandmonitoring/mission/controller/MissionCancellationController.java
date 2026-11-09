package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.request.MissionCancelRequest;
import com.ondemandmonitoring.mission.dto.response.MissionCancellationResponse;
import com.ondemandmonitoring.mission.service.IMissionCancellationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN') or @missionAuthorizationService.canCancelMission(#missionId)")
@RequestMapping("/api/missions/{missionId}/cancel")
public class MissionCancellationController {
    private final IMissionCancellationService cancellation;

    @PostMapping
    public ApiResponse<MissionCancellationResponse> cancel(@PathVariable String missionId,
            @Valid @RequestBody MissionCancelRequest request) {
        return ApiResponse.ok(cancellation.cancel(missionId, request));
    }
}
