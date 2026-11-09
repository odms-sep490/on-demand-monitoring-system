package com.ondemandmonitoring.media.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.media.dto.response.OperatorMissionMediaResponse;
import com.ondemandmonitoring.media.service.IOperatorMissionMediaService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operator/missions/{missionId}/media")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
@Validated
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OperatorMissionMediaController {
    
    IOperatorMissionMediaService mediaService;

    @GetMapping
    public ApiResponse<PageResponse<OperatorMissionMediaResponse>> list(@PathVariable String missionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(mediaService.listAvailable(missionId, page, size));
    }

    @GetMapping("/{mediaId}")
    public ApiResponse<OperatorMissionMediaResponse> get(@PathVariable String missionId,
            @PathVariable String mediaId) {
        return ApiResponse.ok(mediaService.getAvailable(missionId, mediaId));
    }
}
