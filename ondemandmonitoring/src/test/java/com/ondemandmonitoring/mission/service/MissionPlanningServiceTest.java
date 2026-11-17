package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionPlanningServiceTest {
    @org.mockito.Mock OrderRepository orders;
    @org.mockito.Mock MissionRepository missions;
    @org.mockito.Mock OrderChecklistItemRepository items;
    @org.mockito.Mock MissionChecklistAssignmentRepository assignments;
    @org.mockito.Mock MissionChecklistExecutionRepository executions;
    @org.mockito.Mock MissionStaffAssignmentRepository staff;
    @org.mockito.Mock MissionDeviceAssignmentRepository devices;
    @org.mockito.Mock ResourceTimeLockRepository locks;
    @org.mockito.Mock UserScheduleRepository schedules;
    @org.mockito.Mock IResourceReservationService resources;
    @org.mockito.Mock IMissionChecklistMaterializationService materializer;
    @org.mockito.Mock MissionMapper mapper;
    @org.mockito.Mock InvoiceRepository invoices;
    @org.mockito.Mock IMissionPaymentEligibilityService paymentEligibility;
    @org.mockito.Mock com.ondemandmonitoring.user.service.AuthenticatedUserResolver actor;
    @org.mockito.InjectMocks MissionService service;
    AutoCloseable mocks;
    Order order;
    Mission mission;
    MissionChecklistAssignment assignment;

    @BeforeEach void setup() {
        mocks = org.mockito.MockitoAnnotations.openMocks(this);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "missionChecklistAssignmentRepository", assignments);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "missionChecklistExecutionRepository", executions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "orderChecklistItemRepository", items);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "missionPlanningActivationService",
                new MissionPlanningActivationService(materializer, staff, devices));
        order = new Order(); order.setId("o"); order.setOrderStatus(OrderStatus.APPROVED);
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED); order.getExecutionPlan().setRevision(5);
        mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.PLANNING);
        var item = new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setAssignmentStatus(ChecklistAssignmentStatus.ASSIGNED);
        assignment = new MissionChecklistAssignment(); assignment.setId("a"); assignment.setMission(mission);
        assignment.setMissionId("m"); assignment.setOrderId("o"); assignment.setOrderChecklistItemId("i"); assignment.setOrderChecklistItem(item);
        var crew = Arrays.stream(MissionStaffRole.values())
                .map(role -> MissionStaffAssignment.builder().staff(new com.ondemandmonitoring.user.domain.User())
                        .assignedRole(role).responseStatus(MissionStaffAssignmentStatus.PENDING).build()).toList();
        var equipment = List.of(
                MissionDeviceAssignment.builder().device(new com.ondemandmonitoring.device.domain.Device()).deviceRole(DeviceRole.MAIN).build());
        when(staff.findByMissionId("m")).thenReturn(crew);
        when(devices.findByMissionId("m")).thenReturn(equipment);
        when(staff.findAllByMissionIdAndIsCurrentTrue("m")).thenReturn(crew);
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("m")).thenReturn(equipment);
        when(missions.findOrderIdByMissionId("m")).thenReturn(Optional.of("o"));
        when(orders.findById("o")).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate("o")).thenReturn(Optional.of(order));
        when(invoices.findByOrderId("o")).thenReturn(Optional.of(new Invoice()));
        when(paymentEligibility.isDepositSatisfied(any())).thenReturn(true);
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(List.of(mission));
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of(assignment));
        when(assignments.findActiveByOrderId("o")).thenReturn(List.of(assignment));
        when(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("o"))).thenReturn(List.of(item));
    }

    @AfterEach void close() throws Exception { mocks.close(); }

    void error(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(ApiException.class, action::run).getErrorCode());
    }

    @Test void confirmsAdditionalMissionWithoutChangingExistingRuntimeMissions() {
        var sibling = new Mission(); sibling.setId("sibling"); sibling.setOrder(order); sibling.setStatus(MissionStatus.IN_PROGRESS);
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(List.of(mission, sibling));
        service.confirmMissionPlanning("m");
        verify(materializer).materialize(mission);
        assertEquals(MissionStatus.PENDING_CONFIRMATION, mission.getStatus());
        assertEquals(MissionStatus.IN_PROGRESS, sibling.getStatus());
        assertEquals(ExecutionPlanStatus.FINALIZED, order.getExecutionPlan().getStatus());
        assertEquals(6, order.getExecutionPlan().getRevision());
    }

    @Test void confirmRejectsDraftOrderAndMissingChecklistWithoutWrites() {
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.DRAFT);
        error(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED, () -> service.confirmMissionPlanning("m"));
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of());
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, () -> service.confirmMissionPlanning("m"));
        verifyNoInteractions(materializer); verify(missions, never()).saveAndFlush(any());
    }

    @Test void confirmRejectsMissingResourcesAndIncompleteOrderCoverage() {
        var crew = staff.findByMissionId("m");
        when(staff.findByMissionId("m")).thenReturn(List.of());
        error(ErrorCode.INVALID_REQUEST, () -> service.confirmMissionPlanning("m"));
        when(staff.findByMissionId("m")).thenReturn(crew);
        when(assignments.findActiveByOrderId("o")).thenReturn(List.of());
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, () -> service.confirmMissionPlanning("m"));
        verifyNoInteractions(materializer);
    }

    @Test void cancellingDraftReleasesResourcesButPreservesAuditedHistory() {
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.DRAFT);
        when(actor.getCurrentUserId()).thenReturn("manager");
        when(staff.findByMissionId("m")).thenReturn(List.of());
        when(devices.findByMissionId("m")).thenReturn(List.of());
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of(assignment));
        var missionSchedule = UserSchedule.builder().scheduleType(UserScheduleType.MISSION).build();
        var leave = UserSchedule.builder().scheduleType(UserScheduleType.LEAVE).build();
        when(schedules.findByReferenceId("m")).thenReturn(List.of(missionSchedule, leave));
        service.cancelDraftMission("m");
        assertEquals(ChecklistAssignmentStatus.UNASSIGNED, assignment.getOrderChecklistItem().getAssignmentStatus());
        assertEquals(6, order.getExecutionPlan().getRevision());
        assertEquals(MissionStatus.CANCELLED, mission.getStatus());
        assertNotNull(mission.getCancellationAt());
        assertNotNull(assignment.getRemovedAt());
        assertEquals("manager", assignment.getRemovedBy());
        assertEquals(com.ondemandmonitoring.userschedule.enums.UserScheduleStatus.CANCELLED,missionSchedule.getStatus());
        verify(resources).release("m","MISSION_CANCELLED");
        verify(schedules).saveAll(List.of(missionSchedule));
        verify(assignments,never()).deleteAll(any());
        verify(missions,never()).delete(any(Mission.class));
    }

    @Test void cannotDeleteFinalizedOrExecutedMission() {
        error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT, () -> service.cancelDraftMission("m"));
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.DRAFT); mission.setStatus(MissionStatus.IN_PROGRESS);
        error(ErrorCode.MISSION_STATUS_INVALID, () -> service.cancelDraftMission("m"));
        mission.setStatus(MissionStatus.PLANNING);
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(new MissionChecklistExecution()));
        error(ErrorCode.MISSION_STATUS_INVALID, () -> service.cancelDraftMission("m"));
        verify(missions, never()).delete(any(Mission.class)); verifyNoInteractions(resources);
    }
}
