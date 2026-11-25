package com.ondemandmonitoring.aiops.controller;

import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.JudgeCalibrationView;
import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.PromptExperimentView;
import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.PromptLabOverview;
import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.SupervisorEvaluationView;
import com.ondemandmonitoring.aiops.service.IAiSupervisorService;
import com.ondemandmonitoring.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai-ops")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AiSupervisorController {

    private final IAiSupervisorService supervisor;

    @GetMapping("/supervisor/evaluations")
    public ApiResponse<List<SupervisorEvaluationView>> listEvaluations(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(supervisor.listEvaluations(model, severity, status));
    }

    @GetMapping("/supervisor/calibrations")
    public ApiResponse<List<JudgeCalibrationView>> listCalibrations() {
        return ApiResponse.ok(supervisor.listCalibrations());
    }

    @PostMapping("/supervisor/calibrations/run")
    public ApiResponse<JudgeCalibrationView> runCalibration() {
        return ApiResponse.ok(supervisor.runCalibration());
    }

    @GetMapping("/prompts")
    public ApiResponse<PromptLabOverview> getPromptLab() {
        return ApiResponse.ok(supervisor.getPromptLab());
    }

    @PostMapping("/prompts/experiments/{id}/approve")
    public ApiResponse<PromptExperimentView> approveExperiment(@PathVariable UUID id) {
        return ApiResponse.ok(supervisor.approveExperiment(id));
    }

    @PostMapping("/prompts/experiments/{id}/rollback")
    public ApiResponse<PromptExperimentView> rollbackExperiment(@PathVariable UUID id) {
        return ApiResponse.ok(supervisor.rollbackExperiment(id));
    }

    @GetMapping("/evaluations/export")
    public ApiResponse<Map<String, Object>> exportBenchmarkReport() {
        return ApiResponse.ok(supervisor.exportBenchmarkReport());
    }
}
