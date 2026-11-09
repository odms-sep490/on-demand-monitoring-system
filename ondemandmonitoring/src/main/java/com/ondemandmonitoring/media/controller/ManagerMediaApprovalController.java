package com.ondemandmonitoring.media.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.media.dto.response.OperatorMissionMediaResponse;
import com.ondemandmonitoring.media.service.IManagerMediaApprovalService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/missions/{missionId}/media-approvals")
@Validated
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ManagerMediaApprovalController {

    IManagerMediaApprovalService approvals;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN') or @missionAuthorizationService.canReviewMissionMedia(#missionId)")
    public ApiResponse<PageResponse<OperatorMissionMediaResponse>> listPending(
            @PathVariable String missionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(approvals.listPending(missionId, page, size));
    }

    @PostMapping("/{mediaId}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN') or @missionAuthorizationService.canReviewMissionMedia(#missionId)")
    public ApiResponse<OperatorMissionMediaResponse> approve(
            @PathVariable String missionId,
            @PathVariable String mediaId) {
        return ApiResponse.ok(approvals.approve(missionId, mediaId));
    }

    @PostMapping("/{mediaId}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN') or @missionAuthorizationService.canReviewMissionMedia(#missionId)")
    public ApiResponse<OperatorMissionMediaResponse> reject(
            @PathVariable String missionId,
            @PathVariable String mediaId) {
        return ApiResponse.ok(approvals.reject(missionId, mediaId));
    }
}
