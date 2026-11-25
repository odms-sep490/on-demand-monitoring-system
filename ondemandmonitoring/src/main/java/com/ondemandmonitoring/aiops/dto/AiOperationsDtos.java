package com.ondemandmonitoring.aiops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiOperationsDtos {
    private AiOperationsDtos() {}

    public record AiModelView(
            String id,
            String code,
            String name,
            String version,
            String modelType,
            String capability,
            String source,
            String license,
            String checksumSha256,
            String runtime,
            String device,
            String endpoint,
            String modelSize,
            String status,
            boolean enabled,
            Map<String, Object> config,
            Instant lastEvaluatedAt,
            Instant updatedAt
    ) {}

    public record ServiceHealthView(
            String code,
            String name,
            String endpoint,
            String status,
            long latencyMs,
            String detail,
            List<String> loadedModels,
            Instant checkedAt
    ) {}

    public record SystemResourceView(
            double cpuUsagePercent,
            double memoryUsagePercent,
            long memoryUsedMb,
            long memoryTotalMb,
            long jvmHeapUsedMb,
            long jvmHeapMaxMb,
            int availableProcessors,
            String osName
    ) {}

    public record DailyVolumePoint(
            String day,
            long requests,
            long errors
    ) {}

    public record AiOverviewView(
            int totalModels,
            int activeModels,
            int degradedModels,
            long requestsToday,
            double successRatePercent,
            long p95LatencyMs,
            int openIncidents,
            List<DailyVolumePoint> volumeSeries,
            List<AiModelView> models,
            List<ServiceHealthView> services,
            SystemResourceView resources,
            List<AiIncidentView> activeIncidents,
            Instant generatedAt
    ) {}

    public record TraceSpan(
            String name,
            long durationMs,
            String status
    ) {}

    public record AiTraceView(
            String id,
            String traceId,
            String userId,
            String userRole,
            String modelCode,
            String modelVersion,
            String promptVersion,
            String feature,
            String resourceId,
            String inputRef,
            String outputRef,
            int durationMs,
            int httpStatus,
            String inferenceDevice,
            boolean fallbackUsed,
            String validationStatus,
            String securityDecision,
            String humanReviewStatus,
            List<TraceSpan> spans,
            Instant createdAt
    ) {}

    public record AiEvaluationView(
            String id,
            String modelCode,
            String modelVersion,
            String suiteName,
            String metricName,
            double score,
            double passRate,
            double targetScore,
            boolean passed,
            int totalCases,
            int passedCases,
            int failedCases,
            int skippedCases,
            int errorCases,
            String suiteGroup,
            String datasetVersion,
            String judgeModel,
            String promptVersion,
            String runId,
            Double baselineDelta,
            int avgLatencyMs,
            int p95LatencyMs,
            int liveScored,
            int livePassed,
            String sourceArtifact,
            Instant createdAt
    ) {}

    public record EvaluationCaseView(
            String testCaseId,
            String metric,
            String suiteGroup,
            String category,
            String difficulty,
            String language,
            String partition,
            String severity,
            String input,
            int turnsCount,
            String expectedBehavior,
            String actualOutput,
            String evaluationMode,
            Double score,
            double threshold,
            boolean passed,
            String status,
            String judgeReason,
            String rootCause,
            Integer latencyMs,
            Integer inputTokens,
            Integer outputTokens,
            List<String> toolCalls,
            String evaluatedAt
    ) {}

    public record EvaluationJobView(
            String id,
            String status,
            String mode,
            String metricFilter,
            String partition,
            int totalCases,
            int completedCases,
            int passedCases,
            int failedCases,
            int errorCases,
            String currentMetric,
            String currentCase,
            long elapsedMs,
            String lastCheckpoint,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record StartEvaluationJobRequest(
            String mode,
            String metric,
            String partition,
            Integer livePerMetric
    ) {}

    public record GuardrailPolicyView(
            String id,
            String risk,
            String protection,
            String layer,
            boolean enforced
    ) {}

    public record AiSecurityEventView(
            String id,
            String traceId,
            String eventType,
            String riskCategory,
            String severity,
            String userId,
            String feature,
            String decision,
            String summary,
            Instant createdAt
    ) {}

    public record AiSecurityOverviewView(
            List<GuardrailPolicyView> policies,
            List<AiSecurityEventView> events,
            long blockedCount,
            long totalEvents
    ) {}

    public record AiIncidentView(
            String id,
            String title,
            String severity,
            String status,
            String modelCode,
            String serviceEndpoint,
            String summary,
            String recommendedAction,
            String createdBy,
            String resolvedBy,
            Instant createdAt,
            Instant resolvedAt
    ) {}

    public record ToggleModelRequest(@NotNull Boolean enabled) {}

    public record UpdateModelConfigRequest(@NotNull Map<String, Object> config) {}

    public record CreateIncidentRequest(
            @NotBlank String title,
            @NotBlank String severity,
            String modelCode,
            String serviceEndpoint,
            @NotBlank String summary,
            String recommendedAction
    ) {}
}
