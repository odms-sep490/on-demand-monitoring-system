package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest;
import com.ondemandmonitoring.mission.mapper.MissionChecklistAssignmentMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionChecklistAssignmentServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionChecklistAssignmentServiceTest {
    OrderRepository orders=mock(OrderRepository.class);
    MissionRepository missions=mock(MissionRepository.class);
    OrderChecklistItemRepository items=mock(OrderChecklistItemRepository.class);
    MissionChecklistAssignmentRepository assignments=mock(MissionChecklistAssignmentRepository.class);
    MissionChecklistExecutionRepository executions=mock(MissionChecklistExecutionRepository.class);
    AuthenticatedUserResolver actor=mock(AuthenticatedUserResolver.class);
    com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService acceptedGuidance=mock(com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService.class);
    MissionChecklistAssignmentServiceImpl service=new MissionChecklistAssignmentServiceImpl(orders,missions,items,assignments,executions,actor,
            org.mapstruct.factory.Mappers.getMapper(MissionChecklistAssignmentMapper.class),acceptedGuidance);
    Order order; Mission mission; OrderChecklistItem item;
    @BeforeEach void setup() {
        order=new Order(); order.setId("o"); order.setExecutionPlanRevision(2);
        mission=new Mission(); mission.setId("m"); mission.setOrder(order);
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.PLANNING);
        item=new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setContent("Snapshot");
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(orders.findById("o")).thenReturn(Optional.of(order)); when(items.findById("i")).thenReturn(Optional.of(item));
        when(actor.getCurrentUserId()).thenReturn("manager");
    }
    ChecklistAssignmentSelectionRequest request(String... ids) {
        return new ChecklistAssignmentSelectionRequest(2L,Arrays.stream(ids).map(ChecklistAssignmentSelectionRequest.Item::new).toList());
    }
    void error(ErrorCode code,Runnable action) { assertEquals(code,assertThrows(ApiException.class,action::run).getErrorCode()); verify(assignments,never()).saveAllAndFlush(any()); }
    @Test void loadsOrderAndMissionAndIncrementsPublicRevisionWithoutExecutions() {
        var result=service.replace("m",request("i")); var sequence=inOrder(orders,missions);
        sequence.verify(missions).findById("m"); sequence.verify(orders).findById("o"); sequence.verify(missions).findById("m");
        assertEquals(3,result.revision()); verify(executions,never()).saveAllAndFlush(any());
        verify(assignments).saveAllAndFlush(argThat(rows->rows.iterator().hasNext() && rows.iterator().next().getDisplayOrder()==0));
    }
    @Test void duplicateSelectionRejectedBeforeDiff() { error(ErrorCode.CHECKLIST_ASSIGNMENT_DUPLICATE,()->service.replace("m",request("i","i"))); }
    @Test void acceptedSnapshotMissingCustomItemRejectsSelectionBeforeDiff() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        when(acceptedGuidance.findForOrder("o")).thenReturn(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,List.of()));
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID,()->service.replace("m",request("i")));
        verify(orders,never()).saveAndFlush(any());
        verify(items,never()).saveAll(any());
    }
    @Test void legacyAcceptedQuoteAllowsCustomSelection() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        assertEquals(3,service.replace("m",request("i")).revision());
        verify(acceptedGuidance,times(1)).findForOrder("o");
    }
    @Test void acceptedCustomSnapshotAllowsSelectionEvenWhenDraftVersionDiffers() {
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setGuidanceVersion(99);
        when(acceptedGuidance.findForOrder("o")).thenReturn(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,List.of(
                new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item("i",1,2,"Accepted",List.of("Step"),List.of("Input"),List.of("Output")))));
        assertEquals(3,service.replace("m",request("i")).revision());
        verify(acceptedGuidance,times(1)).findForOrder("o");
    }
    @Test void staleRevisionRejectedBeforeDiff() { order.setExecutionPlanRevision(3); error(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT,()->service.replace("m",request("i"))); }
    @Test void crossOrderSelectionRejectedBeforeDiff() {
        var foreign=new Order(); foreign.setId("other"); item.setOrder(foreign);
        error(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER,()->service.replace("m",request("i")));
    }
    @Test void finalizedOrderAllowsUnconfirmedMissionSelectionAndAdvancesRevision() {
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        assertEquals(3, service.replace("m", request("i")).revision());
        verify(executions, never()).saveAllAndFlush(any());
    }
    @Test void confirmedMissionCannotRewriteSelection() {
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.READY);
        error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT, () -> service.replace("m", request("i")));
    }
    @Test void materializedMissionCannotRewriteSelectionEvenInDraft() {
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(new MissionChecklistExecution()));
        error(ErrorCode.MISSION_PLANNING_ONLY, () -> service.replace("m", request("i")));
    }
    @Test void removingSelectionIsAuditedNotDeleted() {
        var a=new MissionChecklistAssignment(); a.setId("a"); a.setMissionId("m"); a.setOrderId("o"); a.setOrderChecklistItemId("i"); a.setOrderChecklistItem(item);
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of(a));
        service.replace("m",request()); assertEquals("manager",a.getRemovedBy()); assertNotNull(a.getRemovedAt());
        verify(assignments,never()).delete(any()); assertEquals(3,order.getExecutionPlanRevision());
    }
}
