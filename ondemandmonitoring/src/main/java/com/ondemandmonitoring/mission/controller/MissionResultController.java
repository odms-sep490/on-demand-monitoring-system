package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.request.MissionResultRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResultResponse;
import com.ondemandmonitoring.mission.service.IMissionResultService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/missions/{missionId}/result")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MissionResultController {

    IMissionResultService missionResultService;

    @GetMapping
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public ResponseEntity<ApiResponse<MissionResultResponse>> get(@PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(missionResultService.getByMissionId(missionId)));
    }

    @PostMapping
    @PreAuthorize("@missionAuthorizationService.canSubmitMissionResult(#missionId)")
    public ResponseEntity<ApiResponse<MissionResultResponse>> createOrUpdate(
            @PathVariable String missionId,
            @Valid @RequestBody MissionResultRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Mission result saved", missionResultService.upsert(missionId, request)));
    }

    @PutMapping
    @PreAuthorize("@missionAuthorizationService.canSubmitMissionResult(#missionId)")
    public ResponseEntity<ApiResponse<MissionResultResponse>> update(
            @PathVariable String missionId,
            @Valid @RequestBody MissionResultRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Mission result updated", missionResultService.upsert(missionId, request)));
    }
}
