package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest;
import com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionChecklistExecutionServiceImpl;
import com.ondemandmonitoring.mission.service.IChecklistEvidenceService;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionChecklistExecutionServiceTest {
    MissionRepository missions = mock(MissionRepository.class);
    OrderChecklistItemRepository items = mock(OrderChecklistItemRepository.class);
    MissionChecklistAssignmentRepository assignments = mock(MissionChecklistAssignmentRepository.class);
    MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
    MissionResultRepository results = mock(MissionResultRepository.class);
    IMissionAuthorizationService auth = mock(IMissionAuthorizationService.class);
    AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    IChecklistEvidenceService evidence = mock(IChecklistEvidenceService.class);
    com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService acceptedGuidance = mock(com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService.class);
    MissionChecklistExecutionServiceImpl service = new MissionChecklistExecutionServiceImpl(missions,
            new com.ondemandmonitoring.mission.service.impl.MissionChecklistScopeServiceImpl(assignments, executions),
            executions, results, auth, resolver, org.mapstruct.factory.Mappers.getMapper(MissionChecklistExecutionMapper.class), evidence, acceptedGuidance);
    Mission mission; Order order; OrderChecklistItem item; MissionChecklistExecution execution;

    @BeforeEach void prepare() {
        order = new Order(); order.setId("o");
        // Operational execution requires a finalized plan as well as valid assignment/execution rows.
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        order.setExecutionPlanRevision(1);
        mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.IN_FLIGHT);
        item = new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setContent("Historical");
        item.setEvidencePolicyVersion(0); item.setMinimumEvidenceCount(0); item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_OR_VIDEO);
        execution = new MissionChecklistExecution(); execution.setId("e"); execution.setVersion(0L);
        execution.setMission(mission); execution.setMissionId("m"); execution.setOrderId("o");
        execution.setOrderChecklistItem(item); execution.setOrderChecklistItemId("i");
        var assignment = ScopeTestFixture.link(execution, mission);
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of(assignment));
        when(missions.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(executions.findByIdForUpdate("e")).thenReturn(Optional.of(execution));
        when(executions.findById("e")).thenReturn(Optional.of(execution));
        when(executions.updateIfVersionMatches(anyString(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), any(), nullable(java.time.Instant.class),
                anyString(), any(java.time.Instant.class))).thenAnswer(call -> {
                    execution.setExecutionStatus(ChecklistExecutionStatus.valueOf(call.getArgument(2)));
                    execution.setAssessmentStatus(ChecklistAssessmentStatus.valueOf(call.getArgument(3)));
                    execution.setObservation(call.getArgument(4));
                    execution.setUnableToVerifyReason(call.getArgument(5));
                    execution.setStartedAt(call.getArgument(6));
                    execution.setCompletedAt(call.getArgument(7));
                    execution.setLastModifiedBy(call.getArgument(8));
                    execution.setVersion(execution.getVersion() + 1);
                    return 1;
                });
        when(auth.canExecuteMonitoringChecklist("m")).thenReturn(true);
        when(auth.canSubmitMissionResult("m")).thenReturn(true);
        when(auth.canReviewMissionMedia("m")).thenReturn(true);
        when(auth.canViewMission("m")).thenReturn(true);
        when(resolver.getCurrentUserId()).thenReturn("authenticated-actor");
        when(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("o"))).thenReturn(List.of(item));
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution));
        when(evidence.active("m")).thenReturn(List.of());
        when(evidence.ready(execution, List.of(), false)).thenReturn(true);
        when(evidence.evidenceRequirementsSatisfied(execution, List.of(), false)).thenReturn(true);
        when(evidence.enrich(any(), eq(execution), any())).thenAnswer(call -> call.getArgument(0));
        when(evidence.readinessFailure(execution, List.of(), false)).thenReturn("requirement not met");
    }

    ChecklistExecutionUpdateRequest request(ChecklistExecutionStatus status) {
        var request = new ChecklistExecutionUpdateRequest(); request.setExpectedVersion(execution.getVersion());
        request.setExecutionStatus(status); request.setAssessmentStatus(ChecklistAssessmentStatus.NOT_ASSESSED); return request;
    }
    @Test void draftOrUnversionedPlanStillBlocksExecutionWithOtherwiseValidRows() {
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        assertFalse(service.isReadyForSubmission(mission));
        error(ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED,
                () -> service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS)));
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        order.setExecutionPlanRevision(0);
        assertFalse(service.isReadyForSubmission(mission));
        error(ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED,
                () -> service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS)));
        assertEquals(ChecklistExecutionStatus.PENDING, execution.getExecutionStatus());
        assertEquals(0L, execution.getVersion());
        verifyNoInteractions(resolver);
    }
    @Test void readsAcceptedGuidanceOnceForTheWholeExecutionBatch() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setCustomGuidance(new ChecklistGuidanceContent("Mutable draft", List.of("Draft"), List.of(), List.of()));
        var second = new MissionChecklistExecution(); second.setOrderChecklistItem(item);
        second.setMissionChecklistAssignment(execution.getMissionChecklistAssignment());
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution, second));
        when(evidence.enrich(any(), eq(second), any())).thenAnswer(call -> call.getArgument(0));
        when(acceptedGuidance.findForOrder("o")).thenReturn(acceptedSnapshot());

        var response = service.getByMissionId("m");

        assertEquals(2, response.executions().size());
        response.executions().forEach(row -> assertEquals("Accepted", row.getDescription()));
        verify(acceptedGuidance, times(1)).findForOrder("o");
    }

    @Test void updateResponseUsesAcceptedGuidanceAndLoadsSnapshotOnlyOnce() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        when(acceptedGuidance.findForOrder("o")).thenReturn(acceptedSnapshot());
        var response = service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS));
        assertEquals("Accepted", response.getDescription());
        assertEquals(List.of("Accepted step"), response.getExecutionSteps());
        verify(acceptedGuidance, times(1)).findForOrder("o");
    }

    @Test void legacyCustomReadAndUpdateRemainAvailableWithoutDraftFallback() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setCustomGuidance(new ChecklistGuidanceContent("Mutable draft", List.of("Draft"), List.of(), List.of()));
        assertNull(service.getByMissionId("m").executions().getFirst().getDescription());
        assertNull(service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS)).getExecutionSteps());
    }

    @Test void missingCustomEntryInNewSnapshotBlocksReadAndUpdateBeforeMutation() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        when(acceptedGuidance.findForOrder("o")).thenReturn(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1, List.of()));
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> service.getByMissionId("m"));
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID,
                () -> service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS)));
        assertEquals(ChecklistExecutionStatus.PENDING, execution.getExecutionStatus());
        assertEquals(0L, execution.getVersion());
        verifyNoInteractions(resolver);
    }

    private com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot acceptedSnapshot() {
        return new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1, List.of(
                new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item("i", 1, 2,
                        "Accepted", List.of("Accepted step"), List.of("Accepted input"), List.of("Accepted output"))));
    }
    @Test void pilotMayChangeExecutionStatusButCannotChangeAssessment() {
        when(auth.canSubmitMissionResult("m")).thenReturn(false);
        when(auth.canReviewMissionMedia("m")).thenReturn(false);
        service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS));
        assertEquals(ChecklistExecutionStatus.IN_PROGRESS, execution.getExecutionStatus());
        var changeAssessment = request(ChecklistExecutionStatus.IN_PROGRESS);
        changeAssessment.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
        error(ErrorCode.ACCESS_DENIED, () -> service.update("m", "e", changeAssessment));
        assertEquals(ChecklistAssessmentStatus.NOT_ASSESSED, execution.getAssessmentStatus());
    }
    @Test void inspectorMayAssessButCannotChangePilotExecutionFields() {
        when(auth.canExecuteMonitoringChecklist("m")).thenReturn(false);
        when(auth.canReviewMissionMedia("m")).thenReturn(true);
        var assessment = request(ChecklistExecutionStatus.PENDING);
        assessment.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
        service.update("m", "e", assessment);
        assertEquals(ChecklistAssessmentStatus.COMPLIANT, execution.getAssessmentStatus());

        var executionChange = request(ChecklistExecutionStatus.IN_PROGRESS);
        executionChange.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
        error(ErrorCode.ACCESS_DENIED, () -> service.update("m", "e", executionChange));
        assertEquals(ChecklistExecutionStatus.PENDING, execution.getExecutionStatus());
    }
    void error(ErrorCode code, Runnable call) { assertEquals(code, assertThrows(ApiException.class, call::run).getErrorCode()); }

    @Test void progressesCompletesWithoutChangingSnapshotOrMissionAndClearsReason() {
        when(evidence.ready(execution, List.of(), false)).thenReturn(true);
        service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS));
        assertNotNull(execution.getStartedAt()); assertNull(execution.getCompletedAt());
        var request = request(ChecklistExecutionStatus.COMPLETED); request.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        request.setObservation("  Fence damaged  "); request.setUnableToVerifyReason("obsolete");
        var response = service.update("m", "e", request);
        assertEquals("Fence damaged", response.getObservation()); assertNull(response.getUnableToVerifyReason());
        assertEquals("authenticated-actor", response.getLastModifiedBy()); assertNotNull(response.getCompletedAt());
        assertEquals(MissionStatus.IN_FLIGHT, mission.getStatus()); assertEquals("Historical", item.getContent());
        verify(items, never()).save(any()); verify(missions, never()).save(any());
    }

    @Test void atomicCompletionWithNoAssessmentIsAllowed() {
        when(evidence.ready(execution, List.of(), false)).thenReturn(true);
        var response = service.update("m", "e", request(ChecklistExecutionStatus.COMPLETED));
        assertEquals(ChecklistAssessmentStatus.NOT_ASSESSED, response.getAssessmentStatus());
        assertNotNull(response.getStartedAt()); assertNotNull(response.getCompletedAt());
    }

    @Test void unableRequiresReasonAndTrimsIt() {
        when(evidence.ready(execution, List.of(), false)).thenReturn(true);
        var request = request(ChecklistExecutionStatus.UNABLE_TO_VERIFY);
        error(ErrorCode.INVALID_REQUEST, () -> service.update("m", "e", request));
        request.setUnableToVerifyReason("   "); error(ErrorCode.INVALID_REQUEST, () -> service.update("m", "e", request));
        request.setUnableToVerifyReason("  Weather obstruction  ");
        assertEquals("Weather obstruction", service.update("m", "e", request).getUnableToVerifyReason());
        assertTrue(service.isReadyForSubmission(mission));
        error(ErrorCode.CHECKLIST_EXECUTION_TRANSITION_INVALID,
                () -> service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS)));
    }

    @Test void reverseIsRejectedUntilManagerRejectsResult() {
        when(evidence.ready(execution, List.of(), false)).thenReturn(true);
        service.update("m", "e", request(ChecklistExecutionStatus.COMPLETED));
        error(ErrorCode.CHECKLIST_EXECUTION_TRANSITION_INVALID, () -> service.update("m", "e", request(ChecklistExecutionStatus.PENDING)));
        var result = new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.REJECTED);
        when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        service.update("m", "e", request(ChecklistExecutionStatus.IN_PROGRESS));
        assertNull(execution.getCompletedAt());
    }

    @Test void pendingAndApprovedResultsLockAllMutations() {
        var result = new MissionResult(); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        for (var status : List.of(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, MissionResultApprovalStatus.APPROVED)) {
            result.setApprovalStatus(status);
            error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> service.update("m", "e", request(ChecklistExecutionStatus.COMPLETED)));
        }
        verify(executions, never()).saveAndFlush(any());
    }

    @Test void staleInvalidAndOversizedRequestsAreRejected() {
        var request = request(ChecklistExecutionStatus.COMPLETED); request.setExpectedVersion(9L);
        error(ErrorCode.CONCURRENT_UPDATE, () -> service.update("m", "e", request));
        request.setExpectedVersion(null); error(ErrorCode.INVALID_REQUEST, () -> service.update("m", "e", request));
        request.setExpectedVersion(0L); request.setObservation("x".repeat(2001));
        error(ErrorCode.INVALID_REQUEST, () -> service.update("m", "e", request));
        request.setObservation(null); request.setUnableToVerifyReason("x".repeat(1001));
        error(ErrorCode.INVALID_REQUEST, () -> service.update("m", "e", request));
    }

    @Test void maximumObservationAndUnableReasonAreAccepted() {
        when(evidence.ready(execution, List.of(), false)).thenReturn(true);
        var request = request(ChecklistExecutionStatus.UNABLE_TO_VERIFY);
        request.setObservation("x".repeat(2000));
        request.setUnableToVerifyReason("y".repeat(1000));
        var response = service.update("m", "e", request);
        assertEquals(request.getObservation(), response.getObservation());
        assertEquals(request.getUnableToVerifyReason(), response.getUnableToVerifyReason());
        assertTrue(service.isReadyForSubmission(mission));
    }

    @Test void parentMismatchCrossOrderAndUnknownEntitiesAreDistinct() {
        execution.setMissionId("other"); error(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH,
                () -> service.update("m", "e", request(ChecklistExecutionStatus.COMPLETED)));
        execution.setMissionId("m"); var other = new Order(); other.setId("other"); item.setOrder(other);
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> service.update("m", "e", request(ChecklistExecutionStatus.COMPLETED)));
        error(ErrorCode.CHECKLIST_EXECUTION_NOT_FOUND, () -> service.update("m", "missing", request(ChecklistExecutionStatus.COMPLETED)));
        error(ErrorCode.MISSION_NOT_FOUND, () -> service.update("missing", "e", request(ChecklistExecutionStatus.COMPLETED)));
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> service.requireMaterialized(mission));
    }

    @Test void missingRowsAreNotReadyAndReadsNeverBackfill() {
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of());
        assertFalse(service.getByMissionId("m").readyForSubmission());
        error(ErrorCode.CHECKLIST_NOT_READY, () -> service.requireReadyForSubmission(mission));
        verify(executions, never()).saveAllAndFlush(any());
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of());
        assertTrue(service.getByMissionId("m").legacySnapshot()); assertTrue(service.isReadyForSubmission(mission));
        order.setChecklistSnapshotAt(java.time.Instant.now()); assertFalse(service.getByMissionId("m").legacySnapshot());
    }

    @Test void serviceRechecksActorUnderMissionLock() {
        when(auth.canExecuteMonitoringChecklist("m")).thenReturn(false);
        when(auth.canReviewMissionMedia("m")).thenReturn(false);
        error(ErrorCode.ACCESS_DENIED, () -> service.update("m", "e", request(ChecklistExecutionStatus.COMPLETED)));
        verify(executions, never()).saveAndFlush(any());
        when(auth.canViewMission("m")).thenReturn(false);
        error(ErrorCode.ACCESS_DENIED, () -> service.getByMissionId("m"));
    }
}
