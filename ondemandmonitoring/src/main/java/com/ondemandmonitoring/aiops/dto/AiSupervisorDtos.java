package com.ondemandmonitoring.aiops.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiSupervisorDtos {

    private AiSupervisorDtos() {}

    public record SupervisorEvaluationView(
            String evaluationId,
            String traceId,
            String targetModel,
            String judgeModel,
            String rubricVersion,
            String feature,
            String evaluationStatus,
            Double qualityScore,
            Map<String, Object> metricScores,
            Boolean taskCompleted,
            List<String> hardConstraintViolations,
            List<String> detectedErrors,
            String severity,
            List<String> evidenceReferences,
            String suspectedRootCause,
            List<String> improvementSuggestions,
            boolean humanReviewRequired,
            int evaluationLatencyMs,
            Integer evalInputTokens,
            Integer evalOutputTokens,
            Instant createdAt
    ) {}

    public record JudgeCalibrationView(
            String id,
            String judgeModel,
            String rubricVersion,
            String datasetVersion,
            int totalCases,
            double agreementRate,
            int falsePositives,
            int falseNegatives,
            double abstentionRate,
            List<Map<String, Object>> cases,
            Instant createdAt
    ) {}

    public record PromptVersionView(
            String id,
            String promptKey,
            String version,
            String modelCode,
            String content,
            String status,
            String createdBy,
            String approvedBy,
            Instant activatedAt,
            Instant createdAt
    ) {}

    public record PromptExperimentView(
            String id,
            String promptKey,
            String baselineVersion,
            String candidateVersion,
            double baselineQuality,
            double candidateQuality,
            double baselineTokens,
            double candidateTokens,
            double baselineLatencyMs,
            double candidateLatencyMs,
            double baselineErrorRate,
            double candidateErrorRate,
            int hardConstraintViolations,
            boolean paretoEligible,
            String status,
            String notes,
            String approvedBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record PromptLabOverview(
            List<PromptVersionView> versions,
            List<PromptExperimentView> experiments
    ) {}
}
