package com.ondemandmonitoring.prompt;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PromptDtos {
    private PromptDtos() {}

    public record VariableSpec(
            String name,
            String type,
            boolean required,
            String description
    ) {}

    public record PromptSummaryView(
            String id,
            String promptKey,
            String displayName,
            String description,
            String feature,
            String promptType,
            String modelBinding,
            String languages,
            List<VariableSpec> variableSchema,
            String activeVersionId,
            Integer activeVersionNumber,
            Integer latestVersionNumber,
            int totalVersions,
            boolean enabled,
            Double avgEvalScore,
            Double avgInputTokens,
            Double avgOutputTokens,
            Double avgLatencyMs,
            Instant updatedAt
    ) {}

    public record PromptVersionView(
            String id,
            String promptId,
            String promptKey,
            int versionNumber,
            String templateContent,
            Map<String, Object> modelParameters,
            List<VariableSpec> variableSchema,
            String contentHash,
            String status,
            String changeSummary,
            String parentVersionId,
            String createdBy,
            Instant createdAt,
            String approvedBy,
            Instant approvedAt,
            Instant activatedAt
    ) {}

    public record ActivationHistoryView(
            String id,
            String promptKey,
            String previousVersionId,
            String newVersionId,
            String activatedBy,
            String reason,
            String traceId,
            Instant activatedAt
    ) {}

    public record TestRunView(
            String id,
            String promptVersionId,
            String promptKey,
            String modelName,
            String renderedPrompt,
            Map<String, Object> inputVariables,
            String modelOutput,
            Integer inputTokens,
            Integer outputTokens,
            int latencyMs,
            Double evaluationScore,
            String validationStatus,
            String errorType,
            String executedBy,
            Instant executedAt
    ) {}

    public record ResolvedPrompt(
            String promptKey,
            String versionId,
            int versionNumber,
            String contentHash,
            String renderedContent,
            Map<String, Object> modelParameters,
            boolean staleCacheUsed
    ) {}

    public record ValidationResult(
            boolean valid,
            List<String> discoveredVariables,
            List<String> missingRequiredVariables,
            List<String> unknownVariables,
            List<String> errors,
            int characterCount,
            int estimatedTokens
    ) {}

    public record PreviewResult(
            String renderedPrompt,
            ValidationResult validation
    ) {}

    public record CreateDraftRequest(
            @NotBlank String templateContent,
            Map<String, Object> modelParameters,
            List<VariableSpec> variableSchema,
            String changeSummary,
            String parentVersionId
    ) {}

    public record PreviewRequest(
            Map<String, Object> variables
    ) {}

    public record PlaygroundTestRequest(
            Map<String, Object> variables,
            String userInput,
            String modelOverride,
            Map<String, Object> parameterOverrides
    ) {}

    public record CreatePromptRequest(
            @NotBlank String promptKey,
            @NotBlank String displayName,
            String description,
            @NotBlank String feature,
            String promptType,
            @NotBlank String modelBinding,
            String languages,
            @NotBlank String templateContent,
            List<VariableSpec> variableSchema,
            Map<String, Object> modelParameters
    ) {}

    public record UpdatePromptRequest(
            @NotBlank String displayName,
            String description,
            @NotBlank String feature,
            String promptType,
            @NotBlank String modelBinding,
            String languages,
            Boolean enabled
    ) {}

    public record UpdateDraftRequest(
            @NotBlank String templateContent,
            Map<String, Object> modelParameters,
            List<VariableSpec> variableSchema,
            String changeSummary
    ) {}

    public record ActivateRequest(
            String reason
    ) {}
}
