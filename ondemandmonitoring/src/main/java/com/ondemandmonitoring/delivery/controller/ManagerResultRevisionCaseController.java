package com.ondemandmonitoring.delivery.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.delivery.dto.request.NotifyOutOfScopeRequest;
import com.ondemandmonitoring.delivery.dto.request.ResultRevisionDecisionRequest;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionCaseResponse;
import com.ondemandmonitoring.delivery.service.IResultRevisionCaseService;
import com.ondemandmonitoring.mission.dto.request.CreateResultRevisionReworkMissionRequest;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionReworkMissionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class ManagerResultRevisionCaseController {
    private final IResultRevisionCaseService service;
    private final com.ondemandmonitoring.mission.service.IMissionService missions;

    @PostMapping("/result-revision-cases/{caseId}/rework-plan/finalize")
    public ApiResponse<Void> finalizeReworkPlan(@PathVariable String caseId,
            @Valid @RequestBody com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest request) {
        missions.finalizeReworkPlan(caseId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/orders/{orderId}/result-revision-case")
    public ApiResponse<ResultRevisionCaseResponse> getByOrder(@PathVariable String orderId) {
        return ApiResponse.ok(service.getByOrderId(orderId));
    }

    @PutMapping("/result-revision-cases/{caseId}/items/{caseItemId}/decision")
    public ApiResponse<ResultRevisionCaseResponse> decide(
            @PathVariable String caseId,
            @PathVariable String caseItemId,
            @Valid @RequestBody ResultRevisionDecisionRequest request) {
        return ApiResponse.ok(service.decide(caseId, caseItemId, request));
    }

    @PostMapping("/result-revision-cases/{caseId}/items/{caseItemId}/notify-out-of-scope")
    public ApiResponse<ResultRevisionCaseResponse> notifyOutOfScope(
            @PathVariable String caseId,
            @PathVariable String caseItemId,
            @Valid @RequestBody NotifyOutOfScopeRequest request) {
        return ApiResponse.ok(service.notifyOutOfScope(caseId, caseItemId, request));
    }

    @PostMapping("/result-revision-cases/{caseId}/rework-missions")
    public ApiResponse<ResultRevisionReworkMissionResponse> createReworkMission(
            @PathVariable String caseId,
            @Valid @RequestBody CreateResultRevisionReworkMissionRequest request) {
        return ApiResponse.ok("Rework mission created", service.createReworkMission(caseId, request));
    }
}
