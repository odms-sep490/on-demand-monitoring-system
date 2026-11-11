package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionChecklistResponse;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper;
import com.ondemandmonitoring.mission.mapper.MissionChecklistGuidanceContext;
import com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionChecklistExecutionServiceImpl implements IMissionChecklistExecutionService {
    private final MissionRepository missions;
    private final com.ondemandmonitoring.mission.service.IMissionChecklistScopeService scope;
    private final MissionChecklistExecutionRepository executions;
    private final MissionResultRepository results;
    private final IMissionAuthorizationService authorization;
    private final AuthenticatedUserResolver currentUser;
    private final MissionChecklistExecutionMapper mapper;
    private final com.ondemandmonitoring.mission.service.IChecklistEvidenceService evidenceService;
    private final IAcceptedChecklistGuidanceService acceptedGuidance;

    @Override
    @Transactional(readOnly = true)
    public void requireMaterialized(Mission mission) { scope.requireMaterialized(mission); }

    @Override
    @Transactional
    public void markMissionFailed(String missionId, String reason) {
        Instant now = Instant.now();
        String failureReason = trimmed(reason, 1000);
        if (failureReason == null) failureReason = "Mission failed during flight";
        List<MissionChecklistExecution> missionExecutions = executions.findOrderedByMissionId(missionId);
        for (MissionChecklistExecution execution : missionExecutions) {
            execution.setExecutionStatus(ChecklistExecutionStatus.UNABLE_TO_VERIFY);
            execution.setUnableToVerifyReason(failureReason);
            if (execution.getStartedAt() == null) execution.setStartedAt(now);
            execution.setCompletedAt(now);
            execution.setAssessmentStatus(ChecklistAssessmentStatus.NOT_ASSESSED);
            execution.setAssessmentNote(null);
            execution.setAssessedBy(null);
            execution.setAssessedAt(null);
            execution.setLastModifiedBy(currentUser.getCurrentUserId());
            execution.setUpdatedAt(now);
        }
        executions.saveAll(missionExecutions);
    }

    @Override
    @Transactional(readOnly = true)
    public MissionChecklistResponse getByMissionId(String missionId) {
        Mission mission = requireMission(missionId, false);
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var links = evidenceService.active(missionId);
        var actual = executions.findOrderedByMissionId(missionId);
        var guidance = new MissionChecklistGuidanceContext(acceptedGuidance.findForOrder(mission.getOrder().getId()));
        boolean customerSelectionEditable = authorization.canSelectCustomerEvidence(missionId);
        var rows = actual.stream().map(e -> {
            var response = evidenceService.enrich(mapper.toResponse(e, guidance), e, links);
            response.setCustomerSelectionEditable(customerSelectionEditable);
            return response;
        }).toList();
        boolean operational = scope.isMaterialized(mission) && operationalReady(mission, actual, links);
        boolean finalReady = operational && actual.stream().allMatch(e -> evidenceService.readyForInspectorApproval(e, links));
        var blockers = new java.util.LinkedHashSet<String>();
        rows.forEach(r -> blockers.addAll(r.getBlockingReasons()));
        if (!operational && blockers.isEmpty()) blockers.add(MissionRuntimeGate.isStructurallyFinalized(mission)
                ? "CHECKLIST_EXECUTION_INTEGRITY_INVALID" : "CHECKLIST_ASSIGNMENT_NOT_FINALIZED");
        // Preserve Phase 5 readyForSubmission as package readiness; actor/status/result locks are independent.
        return new MissionChecklistResponse(missionId, mission.getOrder().getChecklistSnapshotAt() == null,
                operational, rows,
                operational, operational
                        && mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.IN_PROGRESS
                        && mission.getActualEndAt() != null && mission.getCompletedAt() == null,
                finalReady, List.copyOf(blockers));
    }

    @Override
    @Transactional
    public ChecklistExecutionResponse update(String missionId, String executionId,
            ChecklistExecutionUpdateRequest request) {
        // All execution mutations and result submissions/reviews lock the same Mission first.
        // This prevents a submit from racing an execution update across different rows.
        Mission mission = requireMission(missionId, true);
        boolean canExecute = authorization.canExecuteMonitoringChecklist(missionId);
        boolean canAssess = authorization.canAssessMonitoringChecklist(missionId);
        if (!canExecute && !canAssess) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var result = results.findByMissionId(missionId);
        boolean rejected = result.map(r -> r.getApprovalStatus() == MissionResultApprovalStatus.REJECTED).orElse(false);
        if (result.map(r -> r.getApprovalStatus() == MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL
                || r.getApprovalStatus() == MissionResultApprovalStatus.APPROVED).orElse(false)) {
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
        }
        // Keep the aggregate lock order (Mission, then its execution row) and make
        // the expected-version check observe the latest committed execution state.
        MissionChecklistExecution execution = executions.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_EXECUTION_NOT_FOUND));
        if (!missionId.equals(execution.getMissionId()))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH);
        scope.requireMaterialized(mission);
        if (!ChecklistAssignmentIntegrity.matches(mission, execution))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        requireSameOrder(mission, execution.getOrderChecklistItem());
        var guidance = new MissionChecklistGuidanceContext(acceptedGuidance.findForOrder(mission.getOrder().getId()));
        guidance.resolve(execution.getOrderChecklistItem());
        if (!Objects.equals(execution.getOrderId(), mission.getOrder().getId()))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
                || request.getExecutionStatus() == null || request.getAssessmentStatus() == null)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (!request.getExpectedVersion().equals(execution.getVersion()))
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        ChecklistExecutionStatus next = request.getExecutionStatus();
        ChecklistExecutionStatus previous = execution.getExecutionStatus();
        String reason = trimmed(request.getUnableToVerifyReason(), 1000);
        String observation = trimmed(request.getObservation(), 2000);
        // Omitted text preserves its owner's report, including when one account holds both roles.
        // An explicit blank string clears optional text.
        if (request.getObservation() == null) observation = execution.getObservation();
        if (request.getUnableToVerifyReason() == null) reason = execution.getUnableToVerifyReason();
        boolean executionChanged = next != previous
                || !Objects.equals(observation, execution.getObservation())
                || !Objects.equals(next == ChecklistExecutionStatus.UNABLE_TO_VERIFY ? reason : null,
                        execution.getUnableToVerifyReason());
        String assessmentNote = trimmed(request.getAssessmentNote(), 2000);
        // Omission preserves inspector-owned text; reporters cannot clear it with a blank.
        if (request.getAssessmentNote() == null) assessmentNote = execution.getAssessmentNote();
        boolean assessmentChanged = request.getAssessmentStatus() != execution.getAssessmentStatus()
                || !Objects.equals(assessmentNote, execution.getAssessmentNote());
        if (executionChanged && !canExecute)
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (assessmentChanged && !canAssess) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (!validTransition(previous, next, rejected))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_TRANSITION_INVALID);
        if (next == ChecklistExecutionStatus.UNABLE_TO_VERIFY && reason == null)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unable to verify requires a non-blank reason");
        Instant now = Instant.now();
        Instant startedAt = next == ChecklistExecutionStatus.PENDING
                ? null : execution.getStartedAt() == null ? now : execution.getStartedAt();
        Instant completedAt = next.isTerminal()
                ? (previous == next ? execution.getCompletedAt() : now) : null;
        String unableToVerifyReason = next == ChecklistExecutionStatus.UNABLE_TO_VERIFY ? reason : null;
        if (executionChanged && next == ChecklistExecutionStatus.COMPLETED) {
            var activeEvidence = evidenceService.active(missionId);
            if (!evidenceService.evidenceRequirementsSatisfied(execution, activeEvidence, false)) {
                String failure = evidenceService.readinessFailure(execution, activeEvidence, false);
                throw new ApiException(ErrorCode.CHECKLIST_NOT_READY,
                        failure == null ? "Attach eligible evidence before completing this checklist item." : failure);
            }
        }
        ChecklistAssessmentStatus nextAssessment = request.getAssessmentStatus();
        String assessedBy = assessmentChanged ? currentUser.getCurrentUserId() : execution.getAssessedBy();
        Instant assessedAt = assessmentChanged ? now : execution.getAssessedAt();
        // Staff changes invalidate acceptance. An account with both roles must explicitly change
        // the assessment in this command to reassess; echoing an old COMPLIANT value is insufficient.
        if (executionChanged && !assessmentChanged) {
            nextAssessment = ChecklistAssessmentStatus.NOT_ASSESSED;
            assessmentNote = null;
            assessedBy = null;
            assessedAt = null;
        }
        // A repeated command still records an audit event and consumes its expected version.
        // Keep the managed entity clean until the atomic version compare-and-set runs;
        // otherwise flushAutomatically would increment @Version before the CAS.
        try {
            int updated = executions.updateIfVersionMatches(
                    executionId,
                    request.getExpectedVersion(),
                    next.name(),
                    nextAssessment.name(),
                    observation,
                    unableToVerifyReason,
                    startedAt,
                    completedAt,
                    currentUser.getCurrentUserId(),
                    now,
                    assessmentNote,
                    assessedBy,
                    assessedAt);
            if (updated != 1) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
            MissionChecklistExecution updatedExecution = executions.findById(executionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_EXECUTION_NOT_FOUND));
            return evidenceService.enrich(mapper.toResponse(updatedExecution, guidance), updatedExecution, evidenceService.active(missionId));
        } catch (org.springframework.dao.DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException exception) {
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        }
    }

    private boolean validTransition(ChecklistExecutionStatus previous, ChecklistExecutionStatus next, boolean rejected) {
        if (previous == next) return true;
        if (previous.isTerminal()) return rejected;
        if (previous == ChecklistExecutionStatus.PENDING)
            return next == ChecklistExecutionStatus.IN_PROGRESS || next.isTerminal();
        return next.isTerminal();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReadyForSubmission(Mission mission) {
        if (!scope.isMaterialized(mission)) return false;
        List<MissionChecklistExecution> actual = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        return operationalReady(mission, actual, links);
    }

    private boolean operationalReady(Mission mission, List<MissionChecklistExecution> actual,
            List<com.ondemandmonitoring.mission.domain.MissionChecklistEvidence> links) {
        return !actual.isEmpty() && actual.stream().allMatch(execution ->
                mission.getOrder().getId().equals(execution.getOrderId())
                && execution.getExecutionStatus().isTerminal()
                && execution.getOrderChecklistItem() != null
                && mission.getOrder().getId().equals(execution.getOrderChecklistItem().getOrder().getId())
                && evidenceService.ready(execution, links, false)
                && (execution.getExecutionStatus() != ChecklistExecutionStatus.UNABLE_TO_VERIFY
                    || execution.getUnableToVerifyReason() != null && !execution.getUnableToVerifyReason().isBlank()));
        // A flight cannot finish without at least one assigned checklist execution.
    }

    @Override
    @Transactional(readOnly = true)
    public void requireReadyForSubmission(Mission mission) {
        if (!isReadyForSubmission(mission)) throw new ApiException(ErrorCode.CHECKLIST_NOT_READY, readinessMessage(mission, false));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReadyForInspectorApproval(Mission mission) {
        if (!scope.isMaterialized(mission)) return false;
        var actual = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        return operationalReady(mission, actual, links)
                && actual.stream().allMatch(e -> evidenceService.readyForInspectorApproval(e, links));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireReadyForInspectorApproval(Mission mission) {
        if (!scope.isMaterialized(mission)) throw new ApiException(ErrorCode.CHECKLIST_NOT_READY);
        var actual = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        if (!operationalReady(mission, actual, links))
            throw new ApiException(ErrorCode.CHECKLIST_NOT_READY, readinessMessage(mission, false));
        for (var execution : actual) {
            if (evidenceService.readyForInspectorApproval(execution, links)) continue;
            String reason;
            if (execution.getExecutionStatus() != ChecklistExecutionStatus.COMPLETED)
                reason = "EXECUTION_NOT_COMPLETED";
            else if (execution.getAssessmentStatus() != ChecklistAssessmentStatus.COMPLIANT)
                reason = "INSPECTOR_COMPLIANT_ASSESSMENT_REQUIRED";
            else reason = evidenceService.readinessFailure(execution,
                    links.stream().filter(com.ondemandmonitoring.mission.domain.MissionChecklistEvidence::isSelectedForCustomer).toList(), true);
            throw new ApiException(ErrorCode.CHECKLIST_NOT_READY,
                    reason == null ? "Selected customer evidence does not satisfy the saved checklist policy." : reason);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReadyForFinalApproval(Mission mission) {
        if (!scope.isMaterialized(mission)) return false;
        var actual = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        return legacyFinalReady(mission, actual, links);
    }

    // Existing manager recovery and historical approved results retain their original evidence semantics.
    private boolean legacyFinalReady(Mission mission, List<MissionChecklistExecution> actual,
            List<com.ondemandmonitoring.mission.domain.MissionChecklistEvidence> links) {
        return actual.stream().allMatch(execution ->
                mission.getOrder().getId().equals(execution.getOrderId())
                && (execution.getExecutionStatus().isTerminal()
                    || execution.getAssessmentStatus() != ChecklistAssessmentStatus.NOT_ASSESSED)
                && execution.getOrderChecklistItem() != null
                && mission.getOrder().getId().equals(execution.getOrderChecklistItem().getOrder().getId())
                && evidenceService.ready(execution, links, true));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireReadyForFinalApproval(Mission mission) {
        if (!isReadyForFinalApproval(mission))
            throw new ApiException(ErrorCode.CHECKLIST_NOT_READY, readinessMessage(mission, true));
    }

    private String readinessMessage(Mission mission, boolean finalApproval) {
        var rows = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        return rows.stream()
                .filter(execution -> !evidenceService.ready(execution, links, finalApproval))
                .filter(execution -> execution.getExecutionStatus() != ChecklistExecutionStatus.UNABLE_TO_VERIFY)
                .map(execution -> {
                    String failure = evidenceService.readinessFailure(execution, links, finalApproval);
                    if (failure != null) return failure;
                    var item = execution.getOrderChecklistItem();
                    return "Checklist item '" + item.getContent() + "' execution status "
                            + execution.getExecutionStatus() + " is not terminal.";
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(finalApproval
                        ? "Approve eligible evidence media before final MissionResult approval."
                        : "Checklist assessment or eligible evidence does not satisfy the saved Order requirement.");
    }


    private void requireSameOrder(Mission mission, OrderChecklistItem item) {
        if (item == null || !Objects.equals(mission.getOrder().getId(), item.getOrder().getId()))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
    }

    private Mission requireMission(String id, boolean lock) {
        return (lock ? missions.findByIdForUpdate(id) : missions.findById(id))
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    }

    private String trimmed(String value, int maxLength) {
        if (value == null) return null;
        if (value.length() > maxLength) throw new ApiException(ErrorCode.INVALID_REQUEST, "Checklist text is too long");
        return value.strip().isEmpty() ? null : value.strip();
    }
}
