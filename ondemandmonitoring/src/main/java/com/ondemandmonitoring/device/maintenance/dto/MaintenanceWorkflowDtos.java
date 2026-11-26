package com.ondemandmonitoring.device.maintenance.dto;

import com.ondemandmonitoring.device.maintenance.enums.AcceptanceDecisionType;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceEvidenceStage;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceReportStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkActionType;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.device.maintenance.enums.PostCheckResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed request and response contracts for the drone maintenance handover,
 * execution, reporting, and manager acceptance workflow.
 */
public final class MaintenanceWorkflowDtos {

    private MaintenanceWorkflowDtos() {
    }

    public record HandoverItemInput(
            @NotBlank String itemName,
            @NotNull @Min(1) Integer expectedQuantity,
            String conditionNote) {
    }

    public record HandoverItemReceiptInput(
            @NotBlank String itemId,
            @NotNull @Min(0) Integer receivedQuantity,
            String actualConditionNote,
            String discrepancyNote) {
    }

    public record CreateTicketWithHandoverRequest(
            @NotBlank String deviceId,
            @NotBlank String issueType,
            @NotBlank String severity,
            @NotBlank String description,
            /** Optional: leave blank to publish the ticket to the shared Technical queue. */
            String technicalUserId,
            String maintenanceReason,
            String overallCondition,
            String existingDamageNotes,
            Instant actualHandoverAt,
            List<@Valid HandoverItemInput> accessories,
            List<String> evidenceIds) {
    }

    public record SaveInboundHandoverRequest(
            Long expectedVersion,
            /** Optional: leave blank to publish the ticket to the shared Technical queue. */
            String technicalUserId,
            @NotBlank String maintenanceReason,
            @NotBlank String overallCondition,
            String existingDamageNotes,
            Instant actualHandoverAt,
            @Valid List<HandoverItemInput> accessories,
            List<String> evidenceIds,
            String idempotencyKey) {
    }

    public record SubmitInboundHandoverRequest(
            @NotNull Long expectedVersion,
            String idempotencyKey) {
    }

    public record TechnicalReceiptRequest(
            @NotNull Long expectedVersion,
            @NotNull Boolean accepted,
            @NotNull Boolean hasDiscrepancy,
            String discrepancyNotes,
            String rejectionReason,
            @Valid List<HandoverItemReceiptInput> itemReceipts,
            List<String> receiptEvidenceIds,
            String idempotencyKey) {
    }

    public record StartWorkRequest(
            @NotNull Long expectedVersion,
            String notes) {
    }

    public record WaitPartsRequest(
            @NotNull Long expectedVersion,
            @NotBlank String reason,
            @NotBlank String partsNeeded,
            Instant expectedAt) {
    }

    public record ResumeWorkRequest(
            @NotNull Long expectedVersion,
            String notes) {
    }

    public record WorkItemRequest(
            @NotNull Long expectedVersion,
            @NotBlank String componentArea,
            String symptom,
            String diagnosis,
            @NotNull MaintenanceWorkActionType actionType,
            String workPerformed,
            Instant startedAt,
            Instant endedAt,
            @NotNull MaintenanceWorkStatus status,
            String resultSummary,
            List<String> evidenceIds) {
    }

    public record PartReplacementRequest(
            @NotNull Long expectedVersion,
            @NotBlank String partName,
            String partNumber,
            String oldSerial,
            String newSerial,
            @NotNull @DecimalMin("0.001") BigDecimal quantity,
            @NotBlank String unit,
            @NotBlank String replacementReason,
            String removedPartCondition,
            @DecimalMin("0.00") BigDecimal costAmount,
            String currency,
            List<String> evidenceIds) {
    }

    public record PostCheckInput(
            @NotBlank String checkName,
            @NotBlank String checkMethod,
            @NotNull PostCheckResult result,
            String notes,
            String notApplicableReason,
            boolean required,
            boolean evidenceRequired,
            List<String> evidenceIds) {
    }

    public record SaveReportDraftRequest(
            @NotNull Long expectedVersion,
            String receivedConditionSummary,
            String faultDiagnosisSummary,
            String workSummary,
            String partsSummary,
            String postCheckSummary,
            String remainingIssues,
            String usageLimitations,
            String followUpRecommendations,
            List<String> beforeEvidenceIds,
            List<String> afterEvidenceIds,
            @Valid List<PostCheckInput> postChecks) {
    }

    public record SubmitReturnHandoverRequest(
            @NotNull Long expectedVersion,
            @NotBlank String managerUserId,
            @NotBlank String postMaintenanceCondition,
            String unfinishedWorkNotes,
            @Valid List<HandoverItemInput> returnedAccessories,
            List<String> evidenceIds,
            String idempotencyKey) {
    }

    public record AcceptReturnRequest(
            @NotNull Long expectedVersion,
            @NotNull Integer reportRevision,
            @NotNull Boolean deviceMatchConfirmed,
            @NotNull Boolean accessoriesMatchConfirmed,
            String verificationNotes,
            String idempotencyKey) {
    }

    public record RequestReworkRequest(
            @NotNull Long expectedVersion,
            @NotNull Integer reportRevision,
            @NotBlank String reason,
            List<String> failedWorkItemIds,
            String idempotencyKey) {
    }

    public record ClaimTicketRequest(
            @NotNull Long expectedVersion) {
    }

    public record ReleaseTicketRequest(
            @NotNull Long expectedVersion,
            @NotBlank String reason) {
    }

    public record CancelWorkflowRequest(
            @NotNull Long expectedVersion,
            @NotBlank String reason) {
    }

    public record RegisterEvidenceReferenceRequest(
            @NotNull MaintenanceEvidenceStage stage,
            String referenceId,
            @NotBlank String storageKey,
            String storageBucket,
            @NotBlank String fileName,
            @NotBlank String contentType,
            @Min(0) long fileSizeBytes,
            String caption) {
    }

    public record ActorSummary(
            String id,
            String fullName,
            String email,
            String role) {
    }

    public record DeviceSummary(
            String id,
            String name,
            String serialNumber,
            String deviceCode,
            String modelName,
            String status) {
    }

    public record HandoverItemResponse(
            String id,
            String itemName,
            Integer expectedQuantity,
            Integer receivedQuantity,
            String conditionNote,
            String actualConditionNote,
            String discrepancyNote) {
    }

    public record HandoverResponse(
            String id,
            HandoverDirection direction,
            Integer roundNumber,
            Integer revision,
            HandoverStatus status,
            ActorSummary fromUser,
            ActorSummary toUser,
            String maintenanceReason,
            String overallCondition,
            String existingDamageNotes,
            String unfinishedWorkNotes,
            Instant actualHandoverAt,
            Instant submittedAt,
            Instant confirmedAt,
            ActorSummary confirmedBy,
            String discrepancyNotes,
            String rejectionReason,
            Integer reportRevision,
            List<String> evidenceIds,
            List<String> receiptEvidenceIds,
            boolean locked,
            List<HandoverItemResponse> items) {
    }

    public record WorkItemResponse(
            String id,
            Integer roundNumber,
            String componentArea,
            String symptom,
            String diagnosis,
            MaintenanceWorkActionType actionType,
            String workPerformed,
            ActorSummary performedBy,
            Instant startedAt,
            Instant endedAt,
            MaintenanceWorkStatus status,
            String resultSummary,
            List<String> evidenceIds) {
    }

    public record PartReplacementResponse(
            String id,
            Integer roundNumber,
            String partName,
            String partNumber,
            String oldSerial,
            String newSerial,
            BigDecimal quantity,
            String unit,
            String replacementReason,
            String removedPartCondition,
            BigDecimal costAmount,
            String currency,
            List<String> evidenceIds,
            ActorSummary recordedBy) {
    }

    public record PostCheckResponse(
            String id,
            String checkName,
            String checkMethod,
            PostCheckResult result,
            String notes,
            String notApplicableReason,
            boolean required,
            boolean evidenceRequired,
            List<String> evidenceIds) {
    }

    public record ReportVersionResponse(
            String id,
            Integer roundNumber,
            Integer revision,
            MaintenanceReportStatus status,
            String receivedConditionSummary,
            String faultDiagnosisSummary,
            String workSummary,
            String partsSummary,
            String postCheckSummary,
            String remainingIssues,
            String usageLimitations,
            String followUpRecommendations,
            List<String> beforeEvidenceIds,
            List<String> afterEvidenceIds,
            ActorSummary authoredBy,
            Instant submittedAt,
            boolean locked,
            List<PostCheckResponse> postChecks) {
    }

    public record AcceptanceDecisionResponse(
            String id,
            String returnHandoverId,
            String reportVersionId,
            Integer roundNumber,
            Integer reportRevision,
            AcceptanceDecisionType decision,
            ActorSummary decidedBy,
            Instant decidedAt,
            boolean deviceMatchConfirmed,
            boolean accessoriesMatchConfirmed,
            String verificationNotes,
            String reworkReason,
            List<String> failedWorkItemIds,
            boolean checkpointCreated,
            boolean deviceUnlocked,
            List<String> remainingBlockers) {
    }

    public record EvidenceResponse(
            String id,
            MaintenanceEvidenceStage stage,
            String referenceId,
            String storageKey,
            String fileName,
            String contentType,
            Long fileSizeBytes,
            String caption,
            ActorSummary uploadedBy,
            Instant uploadedAt,
            String downloadUrl) {
    }

    public record WorkflowEventResponse(
            String id,
            String action,
            MaintenanceWorkflowStage fromStage,
            MaintenanceWorkflowStage toStage,
            Integer roundNumber,
            Integer revision,
            ActorSummary actor,
            String reason,
            List<String> evidenceIds,
            Instant occurredAt) {
    }

    public record MaintenanceQueueItemResponse(
            String ticketId,
            String ticketCode,
            String legacyStatus,
            MaintenanceWorkflowStage stage,
            Long version,
            Integer currentRound,
            boolean legacyRecord,
            String issueType,
            String severity,
            String description,
            DeviceSummary device,
            ActorSummary handedOverBy,
            ActorSummary assignedTechnical,
            ActorSummary receivingManager,
            Instant openedAt,
            Instant workflowUpdatedAt,
            List<String> availableActions) {
    }

    public record MaintenanceWorkflowDetailResponse(
            String ticketId,
            String ticketCode,
            String legacyStatus,
            MaintenanceWorkflowStage stage,
            Long version,
            Integer currentRound,
            boolean legacyRecord,
            String issueType,
            String severity,
            String description,
            String resolutionNotes,
            String waitingReason,
            String waitingPartsNeeded,
            Instant waitingExpectedAt,
            Integer acceptedReportRevision,
            Instant openedAt,
            Instant resolvedAt,
            Instant closedAt,
            Instant workflowUpdatedAt,
            DeviceSummary device,
            ActorSummary handedOverBy,
            ActorSummary assignedTechnical,
            ActorSummary receivingManager,
            List<String> availableActions,
            List<String> validationBlockers,
            List<HandoverResponse> handovers,
            List<WorkItemResponse> workItems,
            List<PartReplacementResponse> partReplacements,
            List<ReportVersionResponse> reportVersions,
            List<AcceptanceDecisionResponse> acceptanceDecisions,
            List<EvidenceResponse> evidences,
            List<WorkflowEventResponse> timeline) {
    }

    public record QualifiedTechnicalOption(
            String id,
            String fullName,
            String email,
            String qualificationSource) {
    }
}
