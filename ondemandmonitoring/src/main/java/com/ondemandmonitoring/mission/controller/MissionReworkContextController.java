package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.response.MissionReworkContextResponse;
import com.ondemandmonitoring.mission.service.IMissionReworkContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/missions/{missionId}/rework-context")
@RequiredArgsConstructor
public class MissionReworkContextController {
    private final IMissionReworkContextService service;

    @GetMapping
    public ApiResponse<MissionReworkContextResponse> get(@PathVariable String missionId) {
        return ApiResponse.ok(service.get(missionId));
    }
}
