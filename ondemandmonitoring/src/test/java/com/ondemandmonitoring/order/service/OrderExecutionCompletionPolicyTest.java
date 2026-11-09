package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderExecutionCompletionPolicyTest {
    OrderRepository orders = mock(OrderRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    MissionResultRepository results = mock(MissionResultRepository.class);
    IMissionChecklistExecutionService checklist = mock(IMissionChecklistExecutionService.class);
    OrderExecutionCompletionPolicy policy = new OrderExecutionCompletionPolicy(orders, missions, results, checklist,
            mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class),
            mock(MissionChecklistAssignmentRepository.class), new com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService());
    Order order;
    Mission first, second;

    @BeforeEach void setup() {
        when(results.findByMissionIds(any())).thenAnswer(call -> {
            java.util.Collection<String> ids = call.getArgument(0);
            return ids.stream().map(results::findByMissionId).flatMap(Optional::stream).toList();
        });
        order = new Order(); order.setId("o"); order.setOrderStatus(OrderStatus.IN_PROGRESS);
        order.getExecutionPlan().setStatus(com.ondemandmonitoring.order.enums.ExecutionPlanStatus.FINALIZED);
        first = mission("a"); second = mission("b");
        when(orders.findByIdForUpdate("o")).thenReturn(Optional.of(order));
        when(missions.findIdsByOrderIdOrderById("o")).thenReturn(List.of("a", "b"));
        when(checklist.isReadyForFinalApproval(any())).thenReturn(true);
    }
    Mission mission(String id) {
        var mission = new Mission(); mission.setId(id); mission.setOrder(order); mission.setStatus(MissionStatus.COMPLETED);
        when(missions.findByIdForUpdate(id)).thenReturn(Optional.of(mission));
        var result = new MissionResult(); result.setMission(mission); result.setStatus(MissionResultStatus.COMPLETED);
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        when(results.findByMissionId(id)).thenReturn(Optional.of(result));
        return mission;
    }
    boolean evaluate() { return policy.completeIfReady(policy.lockAggregate("o")); }

    @Test void locksOrderThenMissionsInDeterministicOrder() {
        when(missions.findIdsByOrderIdOrderById("o")).thenReturn(List.of("b", "a"));
        policy.lockAggregate("o");
        var sequence = inOrder(orders, missions);
        sequence.verify(orders).findByIdForUpdate("o");
        sequence.verify(missions).findIdsByOrderIdOrderById("o");
        sequence.verify(missions).findByIdForUpdate("a"); sequence.verify(missions).findByIdForUpdate("b");
    }
    @Test void oneApprovedMissionCompletesAndReplayDoesNotWriteAgain() {
        when(missions.findIdsByOrderIdOrderById("o")).thenReturn(List.of("a"));
        assertTrue(evaluate()); assertTrue(evaluate());
        verify(orders, times(1)).saveAndFlush(order);
    }
    @Test void twoApprovedMissionsComplete() { assertTrue(evaluate()); assertEquals(OrderStatus.COMPLETED, order.getOrderStatus()); }
    @Test void draftPlanBlocksEvenWhenLegacyMissionsAndResultsAreCompleteAndReady() {
        order.getExecutionPlan().setStatus(com.ondemandmonitoring.order.enums.ExecutionPlanStatus.DRAFT);
        assertFalse(com.ondemandmonitoring.mission.service.impl.MissionRuntimeGate.isStructurallyFinalized(first));
        assertFalse(com.ondemandmonitoring.mission.service.impl.MissionRuntimeGate.isStructurallyFinalized(second));
        assertTrue(checklist.isReadyForFinalApproval(first));
        assertTrue(checklist.isReadyForFinalApproval(second));
        assertEquals(MissionResultApprovalStatus.APPROVED, results.findByMissionId("a").orElseThrow().getApprovalStatus());
        assertEquals(MissionResultApprovalStatus.APPROVED, results.findByMissionId("b").orElseThrow().getApprovalStatus());
        assertFalse(evaluate()); assertEquals(OrderStatus.IN_PROGRESS, order.getOrderStatus());
        verify(orders, never()).saveAndFlush(any());
    }
    @Test void pendingSiblingBlocksWithoutChangingOrderState() {
        results.findByMissionId("b").orElseThrow().setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        assertFalse(evaluate()); assertEquals(OrderStatus.IN_PROGRESS, order.getOrderStatus()); verify(orders, never()).saveAndFlush(any());
    }
    @Test void cancelledSiblingExcluded() { second.setStatus(MissionStatus.CANCELLED); assertTrue(evaluate()); verify(checklist, never()).isReadyForFinalApproval(second); }
    @Test void cancellationMetadataAlsoExcludesSibling() { second.setStatus(MissionStatus.FAILED); second.setCancellationAt(java.time.Instant.now()); assertTrue(evaluate()); }
    @Test void failedSiblingBlocksEvenWithApprovedResult() { second.setStatus(MissionStatus.FAILED); assertFalse(evaluate()); }
    @Test void missingResultBlocks() { when(results.findByMissionId("b")).thenReturn(Optional.empty()); assertFalse(evaluate()); }
    @Test void rejectedResultBlocks() { results.findByMissionId("b").orElseThrow().setApprovalStatus(MissionResultApprovalStatus.REJECTED); assertFalse(evaluate()); }
    @Test void failedReportBlocks() { results.findByMissionId("b").orElseThrow().setStatus(MissionResultStatus.FAILED); assertFalse(evaluate()); }
    @Test void finalReadinessOfPreviouslyApprovedSiblingIsRechecked() {
        when(checklist.isReadyForFinalApproval(first)).thenReturn(false);
        assertFalse(evaluate()); assertEquals(OrderStatus.IN_PROGRESS, order.getOrderStatus());
    }
    @Test void inspectorConfirmedResultsUseSelectedBundleReadinessInsteadOfReserveMedia() {
        results.findByMissionId("a").orElseThrow().setInspectorConfirmed(true);
        when(checklist.isReadyForFinalApproval(first)).thenReturn(false);
        when(checklist.isReadyForInspectorApproval(first)).thenReturn(true);
        assertTrue(evaluate());
        verify(checklist).isReadyForInspectorApproval(first);
        verify(checklist, never()).isReadyForFinalApproval(first);
    }
    @Test void inspectorConfirmedResultWithInvalidSelectionBlocksCompletion() {
        results.findByMissionId("a").orElseThrow().setInspectorConfirmed(true);
        when(checklist.isReadyForInspectorApproval(first)).thenReturn(false);
        assertFalse(evaluate());
        verify(orders, never()).saveAndFlush(any());
    }
    @Test void zeroEffectiveMissionsCannotComplete() { first.setStatus(MissionStatus.CANCELLED); second.setStatus(MissionStatus.CANCELLED); assertFalse(evaluate()); }
    @Test void terminalOrUnapprovedOrderCannotComplete() {
        for (var status : List.of(OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.PENDING)) {
            order.setOrderStatus(status); assertFalse(evaluate()); assertEquals(status, order.getOrderStatus());
        }
    }
    @Test void infrastructureOrUnexpectedReadinessExceptionsAreNotSilenced() {
        when(checklist.isReadyForFinalApproval(first)).thenThrow(new ApiException(ErrorCode.INTERNAL_SERVER_ERROR));
        assertThrows(ApiException.class, this::evaluate);
    }
}
