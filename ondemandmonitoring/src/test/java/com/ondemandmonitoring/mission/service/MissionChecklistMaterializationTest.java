package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionChecklistMaterializationTest {
    MissionChecklistAssignmentRepository assignments=mock(MissionChecklistAssignmentRepository.class);
    MissionChecklistExecutionRepository executions=mock(MissionChecklistExecutionRepository.class);
    com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService acceptedGuidance=mock(com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService.class);
    MissionChecklistMaterializationServiceImpl materializer=new MissionChecklistMaterializationServiceImpl(assignments,executions,acceptedGuidance);
    MissionChecklistScopeServiceImpl scope=new MissionChecklistScopeServiceImpl(assignments,executions);
    Mission mission; MissionChecklistExecution execution; MissionChecklistAssignment assignment;
    @BeforeEach void setup() {
        var order=new Order(); order.setId("o"); order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED); order.setExecutionPlanRevision(4);
        mission=new Mission(); mission.setId("m"); mission.setOrder(order);
        var item=new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setContent("Snapshot");
        execution=new MissionChecklistExecution(); execution.setId("e"); execution.setMission(mission); execution.setMissionId("m"); execution.setOrderId("o");
        execution.setOrderChecklistItem(item); execution.setOrderChecklistItemId("i"); execution.setVersion(7L);
        assignment=ScopeTestFixture.link(execution,mission);
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of(assignment));
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution));
    }
    @Test void reusesExecutionWithoutChangingHistory() {
        execution.setUnableToVerifyReason("Preserve"); materializer.materialize(mission);
        verify(executions).saveAllAndFlush(argThat(rows->!rows.iterator().hasNext()));
        assertEquals("e",execution.getId()); assertEquals(7L,execution.getVersion()); assertEquals("Preserve",execution.getUnableToVerifyReason());
    }
    @Test void acceptedSnapshotMissingCustomItemBlocksMaterializationBeforeWrites() {
        assignment.getOrderChecklistItem().setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        when(acceptedGuidance.findForOrder("o")).thenReturn(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,List.of()));
        invalidMaterialize();
        verify(acceptedGuidance,times(1)).findForOrder("o");
    }
    @Test void legacyAcceptedQuoteAllowsCustomMaterialization() {
        assignment.getOrderChecklistItem().setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        materializer.materialize(mission);
        verify(executions).saveAllAndFlush(any());
        verify(acceptedGuidance,times(1)).findForOrder("o");
    }
    @Test void acceptedCustomSnapshotAllowsCreationWithoutConsultingMutableDraft() {
        var item=assignment.getOrderChecklistItem();
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setGuidanceVersion(99);
        when(acceptedGuidance.findForOrder("o")).thenReturn(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,List.of(
                new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item("i",1,2,"Accepted",List.of("Step"),List.of("Input"),List.of("Output")))));
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of());
        materializer.materialize(mission);
        verify(executions).saveAllAndFlush(argThat(rows->rows.iterator().hasNext()));
        verify(acceptedGuidance,times(1)).findForOrder("o");
    }
    @Test void createsOnlyMissingAssignmentUsingDefaultsAndHistoricalReference() {
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of()); materializer.materialize(mission);
        verify(executions).saveAllAndFlush(argThat(rows->{var e=rows.iterator().next(); return e.getMissionChecklistAssignmentId().equals(assignment.getId())
                && e.getOrderChecklistItem()==assignment.getOrderChecklistItem() && e.getExecutionStatus()==com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.PENDING
                && e.getAssessmentStatus()==com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.NOT_ASSESSED;}));
    }
    @Test void historicalFinalizedRevisionRemainsEligibleDuringAmendmentButFutureRevisionDoesNot() {
        assertTrue(scope.isMaterialized(mission));
        mission.getOrder().setExecutionPlanStatus(ExecutionPlanStatus.DRAFT); assertFalse(scope.isMaterialized(mission));
    }
    @Test void missingExecutionNotReady() { when(executions.findOrderedByMissionId("m")).thenReturn(List.of()); assertFalse(scope.isMaterialized(mission)); }
    @Test void extraExecutionNotReady() { when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of()); assertFalse(scope.isMaterialized(mission)); }
    @Test void removedHistoryIsNeverEffective() { assignment.setRemovedAt(java.time.Instant.now()); assertFalse(scope.isMaterialized(mission)); invalidMaterialize(); }
    @Test void mismatchedSiblingIdentityRejected() { execution.setMissionChecklistAssignmentId("sibling"); assertFalse(scope.isMaterialized(mission)); invalidMaterialize(); }
    @Test void crossOrderRejected() { assignment.setOrderId("other"); assertFalse(scope.isMaterialized(mission)); invalidMaterialize(); }
    @Test void duplicateExecutionRejected() { when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution,execution)); assertFalse(scope.isMaterialized(mission)); invalidMaterialize(); }
    @Test void duplicateAssignmentRejected() { when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of(assignment,assignment)); assertFalse(scope.isMaterialized(mission)); invalidMaterialize(); }
    @Test void emptyFinalizedScopeIsValidButDraftIsNot() {
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of());
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of());
        assertTrue(scope.isMaterialized(mission)); mission.getOrder().setExecutionPlanStatus(ExecutionPlanStatus.DRAFT); assertFalse(scope.isMaterialized(mission));
    }
    @Test void legacyRequiresNoRetroactiveCreationKeyButStillRequiresIntegrity() {
        mission.getOrder().setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        assertFalse(scope.isMaterialized(mission)); execution.setOrderId("other"); assertFalse(scope.isMaterialized(mission));
    }
    void invalidMaterialize() {
        assertEquals(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID,assertThrows(ApiException.class,()->materializer.materialize(mission)).getErrorCode());
        verify(executions,never()).saveAllAndFlush(any());
    }
}
