package com.ondemandmonitoring.device.maintenance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.*;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceEvidenceStage;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceReportPdfService;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Action-oriented REST API for the Drone Maintenance Handover, Execution,
 * Reporting, and Manager Acceptance workflow.
 * Deliberately exposes no arbitrary PATCH status endpoint.
 */
@Tag(name = "Maintenance Workflow", description = "Handover, Technical execution, reporting, and Manager acceptance APIs")
@RestController
@RequestMapping("/api/maintenance-tickets")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
public class MaintenanceWorkflowController {

    private final MaintenanceWorkflowService workflowService;
    private final MaintenanceReportPdfService reportPdfService;

    @Operation(summary = "Create maintenance ticket with inbound handover draft")
    @PostMapping("/handover-drafts")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> createWithInboundDraft(
            @Valid @RequestBody CreateTicketWithHandoverRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.createTicketWithInboundDraft(request)));
    }

    @Operation(summary = "Get maintenance workflow full detail, actions, blockers, and timeline")
    @GetMapping("/{id}/workflow")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> getWorkflow(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.getWorkflowDetail(id)));
    }

    @Operation(summary = "Save or update inbound handover draft (before submission)")
    @PutMapping("/{id}/handover")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> saveInboundDraft(
            @PathVariable String id,
            @Valid @RequestBody SaveInboundHandoverRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.saveInboundHandoverDraft(id, request)));
    }

    @Operation(summary = "Submit inbound handover directly to designated Technical")
    @PostMapping("/{id}/handover")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> submitInboundHandover(
            @PathVariable String id,
            @Valid @RequestBody SubmitInboundHandoverRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.submitInboundHandover(id, request)));
    }

    @Operation(summary = "Qualified Technical claims an unassigned ticket from the shared queue")
    @PostMapping("/{id}/claim")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> claim(
            @PathVariable String id,
            @Valid @RequestBody ClaimTicketRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.claimTicket(id, request)));
    }

    @Operation(summary = "Claiming Technical returns an unreceived ticket to the shared queue")
    @PostMapping("/{id}/release")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> release(
            @PathVariable String id,
            @Valid @RequestBody ReleaseTicketRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.releaseTicket(id, request)));
    }

    @Operation(summary = "Designated Technical confirms receipt (full/discrepancy) or rejects receipt")
    @PostMapping("/{id}/receive")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> receive(
            @PathVariable String id,
            @Valid @RequestBody TechnicalReceiptRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.confirmOrRejectTechnicalReceipt(id, request)));
    }

    @Operation(summary = "Designated Technical starts maintenance work")
    @PostMapping("/{id}/start-work")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> startWork(
            @PathVariable String id,
            @Valid @RequestBody StartWorkRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.startWork(id, request)));
    }

    @Operation(summary = "Designated Technical pauses maintenance waiting for parts or condition")
    @PostMapping("/{id}/wait-parts")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> waitParts(
            @PathVariable String id,
            @Valid @RequestBody WaitPartsRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.pauseWaitingParts(id, request)));
    }

    @Operation(summary = "Designated Technical resumes maintenance from WAITING_PARTS")
    @PostMapping("/{id}/resume-work")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> resumeWork(
            @PathVariable String id,
            @Valid @RequestBody ResumeWorkRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.resumeWork(id, request)));
    }

    @Operation(summary = "Add maintenance work item in current round")
    @PostMapping("/{id}/work-items")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> addWorkItem(
            @PathVariable String id,
            @Valid @RequestBody WorkItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.addWorkItem(id, request)));
    }

    @Operation(summary = "Update maintenance work item in current round")
    @PutMapping("/{id}/work-items/{workItemId}")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> updateWorkItem(
            @PathVariable String id,
            @PathVariable String workItemId,
            @Valid @RequestBody WorkItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.updateWorkItem(id, workItemId, request)));
    }

    @Operation(summary = "Record part replacement in current round")
    @PostMapping("/{id}/part-replacements")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> addPartReplacement(
            @PathVariable String id,
            @Valid @RequestBody PartReplacementRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.addPartReplacement(id, request)));
    }

    @Operation(summary = "Save structured maintenance report draft (new revision if previous was locked)")
    @PutMapping("/{id}/report")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> saveReportDraft(
            @PathVariable String id,
            @Valid @RequestBody SaveReportDraftRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.saveReportDraft(id, request)));
    }

    @Operation(summary = "Submit return handover and lock report revision for Manager acceptance")
    @PostMapping("/{id}/submit-return")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> submitReturn(
            @PathVariable String id,
            @Valid @RequestBody SubmitReturnHandoverRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.submitReturnHandover(id, request)));
    }

    @Operation(summary = "Manager accepts return handover, creates checkpoint, and conditionally unlocks drone")
    @PostMapping("/{id}/accept-return")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> acceptReturn(
            @PathVariable String id,
            @Valid @RequestBody AcceptReturnRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.acceptReturn(id, request)));
    }

    @Operation(summary = "Manager requests rework, opens new round, preserves history, and keeps drone blocked")
    @PostMapping("/{id}/request-rework")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> requestRework(
            @PathVariable String id,
            @Valid @RequestBody RequestReworkRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.requestRework(id, request)));
    }

    @Operation(summary = "Cancel maintenance workflow before Technical receipt")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<MaintenanceWorkflowDetailResponse>> cancel(
            @PathVariable String id,
            @Valid @RequestBody CancelWorkflowRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.cancelWorkflow(id, request)));
    }

    @Operation(summary = "Upload image evidence for a maintenance ticket")
    @PostMapping(value = "/{id}/evidences", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EvidenceResponse>> uploadEvidence(
            @PathVariable String id,
            @RequestParam MaintenanceEvidenceStage stage,
            @RequestParam(required = false) String referenceId,
            @RequestParam(required = false) String caption,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok(
                workflowService.uploadEvidence(id, stage, referenceId, caption, file)));
    }

    @Operation(summary = "Register an existing media storage reference as evidence for a maintenance ticket")
    @PostMapping(value = "/{id}/evidences/reference", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<EvidenceResponse>> registerEvidenceReference(
            @PathVariable String id,
            @Valid @RequestBody RegisterEvidenceReferenceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.registerEvidenceReference(id, request)));
    }

    @Operation(summary = "Retrieve ticket-scoped evidence metadata and signed download URL")
    @GetMapping("/{id}/evidences/{evidenceId}")
    public ResponseEntity<ApiResponse<EvidenceResponse>> getEvidence(
            @PathVariable String id,
            @PathVariable String evidenceId) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.getEvidenceForTicket(id, evidenceId)));
    }

    @Operation(summary = "Export deterministic PDF of a maintenance report revision")
    @GetMapping(value = "/{id}/report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportReportPdf(
            @PathVariable String id,
            @RequestParam(required = false) Integer revision) {
        byte[] pdf = reportPdfService.exportReportPdf(id, revision);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.inline()
                .filename("maintenance-report-" + id + (revision != null ? "-rev-" + revision : "") + ".pdf")
                .build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    @Operation(summary = "List Technical queues (AWAITING_RECEIPT, IN_PROGRESS, or ALL)")
    @GetMapping("/queues/technical")
    public ResponseEntity<ApiResponse<List<MaintenanceQueueItemResponse>>> listTechnicalQueue(
            @RequestParam(defaultValue = "ALL") String queueType) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.listTechnicalQueue(queueType)));
    }

    @Operation(summary = "List Manager return acceptance queue")
    @GetMapping("/queues/manager-acceptance")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<MaintenanceQueueItemResponse>>> listManagerAcceptanceQueue(
            @RequestParam(defaultValue = "false") boolean includeAll) {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.listManagerAcceptanceQueue(includeAll)));
    }

    @Operation(summary = "List active qualified Technical staff (TECHNICAL license or MAINTAINER assignment)")
    @GetMapping("/qualified-technicals")
    public ResponseEntity<ApiResponse<List<QualifiedTechnicalOption>>> listQualifiedTechnicals() {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.listQualifiedTechnicals()));
    }

    @Operation(summary = "List active Managers eligible to accept post-maintenance return")
    @GetMapping("/receiving-managers")
    public ResponseEntity<ApiResponse<List<ActorSummary>>> listReceivingManagers() {
        return ResponseEntity.ok(ApiResponse.ok(workflowService.listReceivingManagers()));
    }
}
