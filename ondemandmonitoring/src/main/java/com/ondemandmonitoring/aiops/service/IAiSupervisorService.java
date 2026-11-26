package com.ondemandmonitoring.aiops.service;

import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.JudgeCalibrationView;
import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.PromptExperimentView;
import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.PromptLabOverview;
import com.ondemandmonitoring.aiops.dto.AiSupervisorDtos.SupervisorEvaluationView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IAiSupervisorService {

    void ensureSchema();

    List<SupervisorEvaluationView> listEvaluations(String model, String severity, String status);

    JudgeCalibrationView runCalibration();

    List<JudgeCalibrationView> listCalibrations();

    PromptLabOverview getPromptLab();

    PromptExperimentView approveExperiment(UUID id);

    PromptExperimentView rollbackExperiment(UUID id);

    Map<String, Object> exportBenchmarkReport();
}
