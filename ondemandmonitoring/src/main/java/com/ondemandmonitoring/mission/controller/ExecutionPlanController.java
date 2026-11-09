package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.dto.response.ExecutionPlanResponse;
import com.ondemandmonitoring.mission.service.IExecutionPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/{orderId}/execution-plan")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class ExecutionPlanController {
    private final IExecutionPlanService service;
    @GetMapping
    public ApiResponse<ExecutionPlanResponse> get(@PathVariable String orderId) { return ApiResponse.ok(service.get(orderId)); }
    @PostMapping("/finalize")
    public ApiResponse<ExecutionPlanResponse> finalizePlan(@PathVariable String orderId,
            @Valid @RequestBody ExecutionPlanFinalizeRequest request) { return ApiResponse.ok(service.finalizePlan(orderId, request)); }
}
