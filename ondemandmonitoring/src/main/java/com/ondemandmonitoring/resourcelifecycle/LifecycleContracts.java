package com.ondemandmonitoring.resourcelifecycle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed API contracts for Smart Workforce & Drone Lifecycle Management.
 */
public final class LifecycleContracts {

    private LifecycleContracts() {
    }

    public record UsageRequest(
            @NotBlank String resourceId,
            @NotBlank @Pattern(regexp = "DEVICE|STAFF") String resourceKind,
            String missionId,
            String assignmentId,
            String role,
            @NotNull Instant startedAt,
            @NotNull Instant endedAt,
            @PositiveOrZero long seconds,
            @PositiveOrZero Integer cycles,
            @PositiveOrZero Double distanceM,
            @NotBlank @Size(max = 200) String idempotencyKey,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 2000) String evidenceReference,
            UUID supersedes) {
    }

    public record UsageResponse(
            UUID id,
            String resourceId,
            String resourceKind,
            String missionId,
            String assignmentId,
            String role,
            Instant startedAt,
            Instant endedAt,
            long actualSeconds,
            long estimatedSeconds,
            Integer cycles,
            Double distanceM,
            String source,
            String status,
            String evidenceReference,
            String reason,
            String idempotencyKey,
            String createdBy,
            Instant createdAt,
            String verifiedBy,
            Instant verifiedAt,
            UUID supersedes) {
    }

    public record VerificationRequest(
            @NotNull Boolean approved,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record PolicyRequest(
            @NotBlank @Pattern(regexp = "MAINTENANCE|WORKFORCE|ALLOCATION") String family,
            @NotBlank String targetId,
            @NotBlank @Size(max = 200) String name,
            @NotNull Instant effectiveAt,
            @NotNull Map<String, Object> rules,
            @NotBlank @Size(max = 2000) String reason,
            @PositiveOrZero long expectedRevision) {
    }

    public record PolicyActionRequest(
            @PositiveOrZero long expectedRevision,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record PolicyResponse(
            UUID id,
            String family,
            String targetId,
            String name,
            long revision,
            boolean active,
            Instant effectiveAt,
            Map<String, Object> rules,
            String reason,
            String createdBy,
            Instant updatedAt) {
    }

    public record PolicyVersionResponse(
            UUID policyId,
            long revision,
            Map<String, Object> snapshot,
            String actorId,
            String action,
            Instant createdAt) {
    }

    public record PolicyImpactPreview(
            UUID policyId,
            String family,
            String targetId,
            int totalEvaluated,
            int newlyBlockedCount,
            int newlyWarningCount,
            List<ImpactedResource> impactedResources) {
    }

    public record ImpactedResource(
            String resourceId,
            String displayName,
            String previousStatus,
            String projectedStatus,
            List<String> reasons) {
    }

    public record MaintenanceCycleResponse(
            UUID id,
            String deviceId,
            String deviceName,
            UUID policyId,
            String policyName,
            int cycle,
            String ticketId,
            String ticketCode,
            String ticketStatus,
            Instant detectedAt,
            String workNotes,
            String evidenceReference,
            String completedBy,
            Instant submittedAt,
            Boolean inspectionPassed,
            String inspectedBy,
            Instant inspectedAt,
            Instant checkpointAt,
            Long checkpointSeconds,
            Long checkpointCycles) {
    }

    public record MaintenanceWorkSubmissionRequest(
            @NotBlank @Size(max = 4000) String workNotes,
            @NotBlank @Size(max = 2000) String evidenceReference,
            List<String> checklistItems,
            List<String> replacedComponents) {
    }

    public record MaintenanceInspectionRequest(
            @NotNull Boolean passed,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record DroneHealthResponse(
            String deviceId,
            String deviceName,
            String serialNumber,
            String modelId,
            String modelName,
            String operationalStatus,
            String maintenanceStatus,
            Long lifetimeActualSeconds,
            long lifetimeEstimatedSeconds,
            Long sinceMaintenanceSeconds,
            long sinceMaintenanceCycles,
            long lifetimeCycles,
            Double measuredDistanceMeters,
            Long remainingSeconds,
            Integer remainingCycles,
            Integer remainingDays,
            Double utilizationPercent,
            UUID activePolicyId,
            String activePolicyName,
            long activePolicyRevision,
            String usageSource,
            boolean blocked,
            String openTicketId,
            UUID openCycleId,
            Instant lastMaintenanceAt,
            List<String> reasons,
            String batteryTrackingStatus) {
    }

    public record StaffWorkloadResponse(
            String staffId,
            String fullName,
            String email,
            long dailyActualSeconds,
            long dailyEstimatedSeconds,
            long weeklyActualSeconds,
            long weeklyEstimatedSeconds,
            long monthlyActualSeconds,
            long monthlyEstimatedSeconds,
            long yearlyActualSeconds,
            long yearlyEstimatedSeconds,
            Long restSecondsBeforeNextMission,
            String workloadStatus,
            Double maxUtilizationPercent,
            boolean blocked,
            UUID activePolicyId,
            String activePolicyName,
            List<String> blockingReasons,
            List<String> warningReasons) {
    }

    public record EligibilityResponse(
            boolean eligible,
            String status,
            List<String> blockingReasons,
            List<String> warningReasons,
            Map<String, Object> facts) {
    }

    public record SpecificationRequest(
            @NotNull Map<String, Object> manufacturerSpecs,
            @NotNull Map<String, Object> operatingSpecs,
            @NotBlank @Size(max = 2000) String sourceReference,
            @PositiveOrZero long expectedRevision,
            boolean verify) {
    }

    public record SpecificationResponse(
            String modelId,
            String modelName,
            String manufacturer,
            Map<String, Object> manufacturerSpecs,
            Map<String, Object> operatingSpecs,
            String sourceReference,
            String verifiedBy,
            Instant verifiedAt,
            long revision,
            Instant updatedAt) {
    }

    public record AlertResponse(
            UUID id,
            String resourceId,
            String missionId,
            String type,
            String severity,
            String description,
            String status,
            Instant detectedAt,
            String acknowledgedBy,
            String resolvedBy,
            String resolutionNote,
            Instant updatedAt) {
    }

    public record AlertActionRequest(
            @NotBlank @Pattern(regexp = "ACKNOWLEDGE|RESOLVE") String action,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record AuditLogResponse(
            UUID id,
            String actorId,
            String action,
            String entityId,
            Map<String, Object> beforeJson,
            Map<String, Object> afterJson,
            String reason,
            Instant createdAt) {
    }

    public record RecommendationResponse(
            UUID evaluationId,
            String missionId,
            String algorithmVersion,
            String componentType,
            long elapsedMs,
            String aiExplanation,
            boolean aiExplanationAvailable,
            List<AllocationScoringEngine.ScoredCandidate> devices,
            List<AllocationScoringEngine.ScoredCandidate> staff) {
    }

    public record ScheduleForecastItem(
            String deviceId,
            String deviceName,
            Long currentSinceMaintenanceSeconds,
            long scheduledProjectedSeconds,
            Long remainingSecondsAfterSchedule,
            boolean projectedBreach,
            String forecastBasis) {
    }

    public record OverviewResponse(
            int totalDevices,
            int healthyDevices,
            int warningDevices,
            int blockedDevices,
            int unknownHealthDevices,
            int totalStaff,
            int warningStaff,
            int blockedStaff,
            int openAlerts,
            int activeMaintenanceCycles,
            int activePolicies,
            String batteryCapabilityNote) {
    }
}
