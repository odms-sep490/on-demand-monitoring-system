package com.ondemandmonitoring.aiops.controller;

import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiEvaluationView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiIncidentView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiModelView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiOverviewView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiSecurityOverviewView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiTraceView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.CreateIncidentRequest;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.EvaluationCaseView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.EvaluationJobView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.ServiceHealthView;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.StartEvaluationJobRequest;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.ToggleModelRequest;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.UpdateModelConfigRequest;
import com.ondemandmonitoring.aiops.service.IAiOperationsService;
import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai-ops")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AiOperationsController {

    private final IAiOperationsService aiOps;

    @GetMapping("/overview")
    public ApiResponse<AiOverviewView> overview() {
        return ApiResponse.ok(aiOps.getOverview());
    }

    @GetMapping("/models")
    public ApiResponse<List<AiModelView>> models() {
        return ApiResponse.ok(aiOps.listModels());
    }

    @PatchMapping("/models/{code}/toggle")
    public ApiResponse<AiModelView> toggleModel(
            @PathVariable String code,
            @Valid @RequestBody ToggleModelRequest request) {
        return ApiResponse.ok(aiOps.toggleModel(code, Boolean.TRUE.equals(request.enabled())));
    }

    @PatchMapping("/models/{code}/config")
    public ApiResponse<AiModelView> updateConfig(
            @PathVariable String code,
            @Valid @RequestBody UpdateModelConfigRequest request) {
        return ApiResponse.ok(aiOps.updateModelConfig(code, request.config()));
    }

    @PostMapping("/models/{code}/probe")
    public ApiResponse<ServiceHealthView> probeModel(@PathVariable String code) {
        return ApiResponse.ok(aiOps.probeModel(code));
    }

    @GetMapping("/services")
    public ApiResponse<List<ServiceHealthView>> services() {
        return ApiResponse.ok(aiOps.checkServices());
    }

    @GetMapping("/traces")
    public ApiResponse<List<AiTraceView>> traces(@RequestParam(defaultValue = "80") int limit) {
        return ApiResponse.ok(aiOps.listTraces(limit));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResponse<AiTraceView> trace(@PathVariable String traceId) {
        return ApiResponse.ok(aiOps.getTrace(traceId));
    }

    @GetMapping("/evaluations")
    public ApiResponse<List<AiEvaluationView>> evaluations() {
        return ApiResponse.ok(aiOps.listEvaluations());
    }

    @PostMapping("/evaluations/sync")
    public ApiResponse<List<AiEvaluationView>> syncEvaluations() {
        return ApiResponse.ok(aiOps.syncDeepEvalFromDisk());
    }

    @GetMapping("/evaluations/cases")
    public ApiResponse<List<EvaluationCaseView>> evaluationCases(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(
                aiOps.listEvaluationCases(metric, status, difficulty, language, search, limit)
        );
    }

    @GetMapping("/evaluations/history")
    public ApiResponse<List<Map<String, Object>>> evaluationHistory(
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(aiOps.listEvaluationHistory(limit));
    }

    @GetMapping("/evaluations/jobs")
    public ApiResponse<List<EvaluationJobView>> evaluationJobs() {
        return ApiResponse.ok(aiOps.listEvaluationJobs());
    }

    @PostMapping("/evaluations/jobs")
    public ApiResponse<EvaluationJobView> startEvaluationJob(
            @RequestBody(required = false) StartEvaluationJobRequest request) {
        return ApiResponse.ok(aiOps.startEvaluationJob(request));
    }

    @PostMapping("/evaluations/jobs/{id}/cancel")
    public ApiResponse<EvaluationJobView> cancelEvaluationJob(@PathVariable UUID id) {
        return ApiResponse.ok(aiOps.cancelEvaluationJob(id));
    }

    @GetMapping("/security")
    public ApiResponse<AiSecurityOverviewView> security() {
        return ApiResponse.ok(aiOps.getSecurityOverview());
    }

    @GetMapping("/incidents")
    public ApiResponse<List<AiIncidentView>> incidents(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(aiOps.listIncidents(status));
    }

    @PostMapping("/incidents")
    public ApiResponse<AiIncidentView> createIncident(
            @Valid @RequestBody CreateIncidentRequest request) {
        return ApiResponse.ok(aiOps.createIncident(request));
    }

    @PatchMapping("/incidents/{id}/resolve")
    public ApiResponse<AiIncidentView> resolveIncident(@PathVariable UUID id) {
        return ApiResponse.ok(aiOps.resolveIncident(id));
    }
}
