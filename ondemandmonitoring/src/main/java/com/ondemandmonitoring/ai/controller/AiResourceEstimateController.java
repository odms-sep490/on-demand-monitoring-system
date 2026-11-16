package com.ondemandmonitoring.ai.controller;

import com.ondemandmonitoring.ai.dto.AiResourceEstimateResponse;
import com.ondemandmonitoring.ai.service.AiResourceEstimateService;
import com.ondemandmonitoring.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/missions/{missionId}/ai")
@RequiredArgsConstructor
@Tag(name = "AI Resource Estimation")
public class AiResourceEstimateController {
    private final AiResourceEstimateService estimates;

    @PostMapping("/resource-estimate")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    @Operation(summary = "Estimate device and staff quantities for a mission using AI")
    public ResponseEntity<ApiResponse<AiResourceEstimateResponse>> estimate(
            @PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "AI resource estimate generated", estimates.estimate(missionId)));
    }
}
