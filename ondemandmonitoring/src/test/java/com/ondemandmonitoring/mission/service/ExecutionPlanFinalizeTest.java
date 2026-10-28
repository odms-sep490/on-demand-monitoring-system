package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.device.domain.Device;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionPlanFinalizeTest {
    OrderRepository orders = mock(OrderRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    OrderChecklistItemRepository items = mock(OrderChecklistItemRepository.class);
    MissionChecklistAssignmentRepository assignments = mock(MissionChecklistAssignmentRepository.class);
    MissionStaffAssignmentRepository staff = mock(MissionStaffAssignmentRepository.class);
    MissionDeviceAssignmentRepository devices = mock(MissionDeviceAssignmentRepository.class);
    IMissionChecklistMaterializationService materializer = mock(IMissionChecklistMaterializationService.class);
    AuthenticatedUserResolver actor = mock(AuthenticatedUserResolver.class);
    ExecutionPlanServiceImpl service = new ExecutionPlanServiceImpl(orders, missions, items, assignments,
            materializer, mock(IMissionChecklistScopeService.class), actor, new ExecutionPlanCoverageService(),
            staff, devices);
    Order order;
    Mission first, second;
    List<MissionChecklistAssignment> selection;

    @BeforeEach void setup() {
        order = new Order(); order.setId("o"); order.setExecutionPlanRevision(3); order.setOrderStatus(OrderStatus.APPROVED);
        first = mission("a"); second = mission("b");
        selection = List.of(assignment(first, "i1"), assignment(second, "i2"));
        when(orders.findById("o")).thenReturn(Optional.of(order));
        when(missions.findIdsByOrderIdOrderById("o")).thenReturn(List.of("b", "a"));
        when(missions.findById("a")).thenReturn(Optional.of(first));
        when(missions.findById("b")).thenReturn(Optional.of(second));
        when(assignments.findActiveByOrderId("o")).thenReturn(selection);
        when(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("o")))
                .thenReturn(selection.stream().map(MissionChecklistAssignment::getOrderChecklistItem).toList());
        when(actor.getCurrentUserId()).thenReturn("manager");
    }

    Mission mission(String id) {
        var m = new Mission(); m.setId(id); m.setOrder(order); m.setStatus(MissionStatus.PLANNING);
        when(staff.findAllByMissionIdAndIsCurrentTrue(id)).thenReturn(Arrays.stream(MissionStaffRole.values())
                .map(role -> MissionStaffAssignment.builder().mission(m).staff(new User()).assignedRole(role)
                        .responseStatus(MissionStaffAssignmentStatus.PENDING).build()).toList());
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(id)).thenReturn(List.of(
                MissionDeviceAssignment.builder().mission(m).device(new Device()).isCurrent(true).deviceRole(DeviceRole.MAIN).build()));
        return m;
    }

    MissionChecklistAssignment assignment(Mission m, String id) {
        var item = new OrderChecklistItem(); item.setId(id); item.setOrder(order);
        var a = new MissionChecklistAssignment(); a.setId("a-" + id); a.setMissionId(m.getId());
        a.setOrderId("o"); a.setOrderChecklistItemId(id); a.setOrderChecklistItem(item); return a;
    }

    void finalizePlan() { service.finalizePlan("o", new ExecutionPlanFinalizeRequest(3L, true)); }
    void error(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(ApiException.class, action::run).getErrorCode());
    }

    @Test void finalizationRequiresResourcesAndCoverageButNotFinanceGeometryOrDates() {
        finalizePlan();
        var sequence = inOrder(orders, missions, materializer);
        sequence.verify(orders).findById("o");
        sequence.verify(missions).findIdsByOrderIdOrderById("o");
        sequence.verify(missions).findById("a"); sequence.verify(missions).findById("b");
        sequence.verify(materializer).materialize(first); sequence.verify(materializer).materialize(second);
        assertEquals(ExecutionPlanStatus.FINALIZED, order.getExecutionPlanStatus());
        assertEquals(4, order.getExecutionPlanRevision());
        assertEquals("manager", order.getExecutionPlanFinalizedBy()); assertNotNull(order.getExecutionPlanFinalizedAt());
        assertEquals(MissionStatus.READY, first.getStatus());
        assertEquals(MissionStatus.READY, second.getStatus());
    }

    @Test void missingCrewOnAnyMissionPreventsEveryMaterialization() {
        when(staff.findAllByMissionIdAndIsCurrentTrue("b")).thenReturn(List.of());
        error(ErrorCode.INVALID_REQUEST, this::finalizePlan);
        verifyNoInteractions(materializer); assertEquals(ExecutionPlanStatus.DRAFT, order.getExecutionPlanStatus());
    }

    @Test void supportDeviceDoesNotSatisfyMainRequirement() {
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("b")).thenReturn(List.of(
                MissionDeviceAssignment.builder().device(new Device()).deviceRole(DeviceRole.SUPPORT).build()));
        error(ErrorCode.INVALID_REQUEST, this::finalizePlan); verifyNoInteractions(materializer);
    }

    @Test void releasedOrRejectedCrewDoesNotCount() {
        var crew = staff.findAllByMissionIdAndIsCurrentTrue("a");
        crew.getFirst().setResponseStatus(MissionStaffAssignmentStatus.REJECTED);
        error(ErrorCode.INVALID_REQUEST, this::finalizePlan);
        crew.getFirst().setResponseStatus(MissionStaffAssignmentStatus.PENDING); crew.getFirst().setReleasedAt(java.time.Instant.now());
        error(ErrorCode.INVALID_REQUEST, this::finalizePlan); verifyNoInteractions(materializer);
    }

    @Test void crossOrderAndUncoveredChecklistCannotFinalize() {
        selection.getFirst().setOrderId("foreign");
        error(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER, this::finalizePlan);
        when(assignments.findActiveByOrderId("o")).thenReturn(List.of());
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, this::finalizePlan); verifyNoInteractions(materializer);
    }

    @Test void emptyMissionCannotHideBehindFullOrderCoverage() {
        selection.get(1).setMissionId("a");
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, this::finalizePlan); verifyNoInteractions(materializer);
    }

    @Test void duplicateCoverageIsRejected() {
        var duplicate = assignment(second, "i1");
        when(assignments.findActiveByOrderId("o")).thenReturn(List.of(selection.get(0), selection.get(1), duplicate));
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, this::finalizePlan); verifyNoInteractions(materializer);
    }

    @Test void invalidStatusAndRevisionNeverMaterialize() {
        error(ErrorCode.INVALID_REQUEST, () -> service.finalizePlan("o", new ExecutionPlanFinalizeRequest(null, false)));
        error(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT, () -> service.finalizePlan("o", new ExecutionPlanFinalizeRequest(2L, true)));
        second.setStatus(MissionStatus.READY);
        error(ErrorCode.MISSION_STATUS_INVALID, this::finalizePlan);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT, this::finalizePlan); verifyNoInteractions(materializer);
    }
}
