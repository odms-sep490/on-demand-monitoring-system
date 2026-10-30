package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionChecklistResponse;
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
    @Transactional(readOnly = true)
    public MissionChecklistResponse getByMissionId(String missionId) {
        Mission mission = requireMission(missionId, false);
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var links = evidenceService.active(missionId);
        var actual = executions.findOrderedByMissionId(missionId);
        var guidance = new MissionChecklistGuidanceContext(acceptedGuidance.findForOrder(mission.getOrder().getId()));
        var rows = actual.stream()
                .map(e -> evidenceService.enrich(mapper.toResponse(e, guidance), e, links)).toList();
        boolean operational = scope.isMaterialized(mission) && operationalReady(mission, actual, links);
        boolean finalReady = operational && actual.stream().allMatch(e -> evidenceService.ready(e, links, true));
        var blockers = new java.util.LinkedHashSet<String>();
        rows.forEach(r -> blockers.addAll(r.getBlockingReasons()));
        if (!operational && blockers.isEmpty()) blockers.add(MissionRuntimeGate.isStructurallyFinalized(mission)
                ? "CHECKLIST_EXECUTION_INTEGRITY_INVALID" : "CHECKLIST_ASSIGNMENT_NOT_FINALIZED");
        // Preserve Phase 5 readyForSubmission as package readiness; actor/status/result locks are independent.
        return new MissionChecklistResponse(missionId, mission.getOrder().getChecklistSnapshotAt() == null,
                operational, rows,
                operational, operational && mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.PENDING_REVIEW,
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
        boolean canAssess = authorization.canReviewMissionMedia(missionId);
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
        boolean executionChanged = next != previous
                || !Objects.equals(observation, execution.getObservation())
                || !Objects.equals(next == ChecklistExecutionStatus.UNABLE_TO_VERIFY ? reason : null,
                        execution.getUnableToVerifyReason());
        boolean assessmentChanged = request.getAssessmentStatus() != execution.getAssessmentStatus();
        if (executionChanged && !canExecute) throw new ApiException(ErrorCode.ACCESS_DENIED);
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
        if (next == ChecklistExecutionStatus.COMPLETED) {
            var activeEvidence = evidenceService.active(missionId);
            if (!evidenceService.evidenceRequirementsSatisfied(execution, activeEvidence, false)) {
                String failure = evidenceService.readinessFailure(execution, activeEvidence, false);
                throw new ApiException(ErrorCode.CHECKLIST_NOT_READY,
                        failure == null ? "Attach eligible evidence before completing this checklist item." : failure);
            }
        }
        // A repeated command still records an audit event and consumes its expected version.
        // Keep the managed entity clean until the atomic version compare-and-set runs;
        // otherwise flushAutomatically would increment @Version before the CAS.
        try {
            int updated = executions.updateIfVersionMatches(
                    executionId,
                    request.getExpectedVersion(),
                    next.name(),
                    request.getAssessmentStatus().name(),
                    observation,
                    unableToVerifyReason,
                    startedAt,
                    completedAt,
                    currentUser.getCurrentUserId(),
                    now);
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
        return actual.stream().allMatch(execution ->
                mission.getOrder().getId().equals(execution.getOrderId())
                && execution.getExecutionStatus().isTerminal()
                && execution.getOrderChecklistItem() != null
                && mission.getOrder().getId().equals(execution.getOrderChecklistItem().getOrder().getId())
                && evidenceService.ready(execution, links, false)
                && (execution.getExecutionStatus() != ChecklistExecutionStatus.UNABLE_TO_VERIFY
                    || execution.getUnableToVerifyReason() != null && !execution.getUnableToVerifyReason().isBlank()));
        // An empty finalized assignment scope is satisfied; never infer current templates or sibling scope.
    }

    @Override
    @Transactional(readOnly = true)
    public void requireReadyForSubmission(Mission mission) {
        if (!isReadyForSubmission(mission)) throw new ApiException(ErrorCode.CHECKLIST_NOT_READY, readinessMessage(mission, false));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReadyForFinalApproval(Mission mission) {
        if (!scope.isMaterialized(mission)) return false;
        var actual = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        return operationalReady(mission, actual, links)
                && actual.stream().allMatch(e -> evidenceService.ready(e, links, true));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireReadyForFinalApproval(Mission mission) {
        if (!scope.isMaterialized(mission)) throw new ApiException(ErrorCode.CHECKLIST_NOT_READY);
        var actual = executions.findOrderedByMissionId(mission.getId());
        var links = evidenceService.active(mission.getId());
        if (!operationalReady(mission, actual, links)) {
            String reason = actual.stream().map(e -> evidenceService.readinessFailure(e, links, false))
                    .filter(Objects::nonNull).findFirst().orElse("Checklist execution is not operationally ready.");
            throw new ApiException(ErrorCode.CHECKLIST_NOT_READY, reason);
        }
        if (!actual.stream().allMatch(e -> evidenceService.ready(e, links, true))) {
            String reason = actual.stream().filter(e -> !evidenceService.ready(e, links, true))
                    .map(e -> {
                        String failure = evidenceService.readinessFailure(e, links, true);
                        if (failure != null) return failure;
                        var item = e.getOrderChecklistItem();
                        return "Checklist item '" + item.getContent() + "' is not eligible for final approval.";
                    }).filter(Objects::nonNull).findFirst().orElse("Evidence is not ready for final approval.");
            throw new ApiException(ErrorCode.CHECKLIST_NOT_READY, reason);
        }
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
                        : "Checklist execution or eligible evidence does not satisfy the saved Order requirement.");
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
