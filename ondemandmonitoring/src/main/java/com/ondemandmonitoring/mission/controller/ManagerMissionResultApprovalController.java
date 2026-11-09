package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResultResponse;
import com.ondemandmonitoring.mission.service.IMissionResultService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/mission-results")
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
@Validated
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ManagerMissionResultApprovalController {

    IMissionResultService missionResultService;

    @GetMapping("/pending")
    public ApiResponse<PageResponse<MissionResultResponse>> listPending(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(missionResultService.listPendingManagerApproval(page, size));
    }

    @PostMapping("/{resultId}/approve")
    public ApiResponse<MissionResultResponse> approve(
            @PathVariable String resultId,
            @Valid @RequestBody(required = false) MissionResultReviewRequest request) {
        return ApiResponse.ok("Mission result approved", missionResultService.approve(resultId, request));
    }

    @PostMapping("/{resultId}/reject")
    public ApiResponse<MissionResultResponse> reject(
            @PathVariable String resultId,
            @Valid @RequestBody(required = false) MissionResultReviewRequest request) {
        return ApiResponse.ok("Mission result rejected", missionResultService.reject(resultId, request));
    }
}
