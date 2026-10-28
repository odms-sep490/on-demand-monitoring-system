package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
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
        org.springframework.test.util.ReflectionTestUtils.setField(service, "checklistMaterializationService", materializer);
        order = new Order(); order.setId("o"); order.setOrderStatus(OrderStatus.APPROVED);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED); order.setExecutionPlanRevision(5);
        mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.PLANNING);
        var item = new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setAssignmentStatus(ChecklistAssignmentStatus.ASSIGNED);
        assignment = new MissionChecklistAssignment(); assignment.setId("a"); assignment.setMission(mission);
        assignment.setMissionId("m"); assignment.setOrderId("o"); assignment.setOrderChecklistItemId("i"); assignment.setOrderChecklistItem(item);
        when(staff.findAllByMissionIdAndIsCurrentTrue("m")).thenReturn(Arrays.stream(MissionStaffRole.values())
                .map(role -> MissionStaffAssignment.builder().staff(new com.ondemandmonitoring.user.domain.User())
                        .assignedRole(role).responseStatus(MissionStaffAssignmentStatus.PENDING).build()).toList());
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("m")).thenReturn(List.of(
                MissionDeviceAssignment.builder().device(new com.ondemandmonitoring.device.domain.Device()).deviceRole(DeviceRole.MAIN).build()));
        when(missions.findOrderIdByMissionId("m")).thenReturn(Optional.of("o"));
        when(orders.findById("o")).thenReturn(Optional.of(order));
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
        var sibling = new Mission(); sibling.setId("sibling"); sibling.setOrder(order); sibling.setStatus(MissionStatus.IN_FLIGHT);
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(List.of(mission, sibling));
        service.confirmMissionPlanning("m");
        verify(materializer).materialize(mission);
        assertEquals(MissionStatus.READY, mission.getStatus());
        assertEquals(MissionStatus.IN_FLIGHT, sibling.getStatus());
        assertEquals(ExecutionPlanStatus.FINALIZED, order.getExecutionPlanStatus());
        assertEquals(6, order.getExecutionPlanRevision());
    }

    @Test void confirmRejectsDraftOrderAndMissingChecklistWithoutWrites() {
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        error(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED, () -> service.confirmMissionPlanning("m"));
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("m")).thenReturn(List.of());
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, () -> service.confirmMissionPlanning("m"));
        verifyNoInteractions(materializer); verify(missions, never()).saveAndFlush(any());
    }

    @Test void confirmRejectsMissingResourcesAndIncompleteOrderCoverage() {
        var crew = staff.findAllByMissionIdAndIsCurrentTrue("m");
        when(staff.findAllByMissionIdAndIsCurrentTrue("m")).thenReturn(List.of());
        error(ErrorCode.INVALID_REQUEST, () -> service.confirmMissionPlanning("m"));
        when(staff.findAllByMissionIdAndIsCurrentTrue("m")).thenReturn(crew);
        when(assignments.findActiveByOrderId("o")).thenReturn(List.of());
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, () -> service.confirmMissionPlanning("m"));
        verifyNoInteractions(materializer);
    }

    @Test void deletingDraftCleansChildrenAndUnassignsChecklistBeforeDeletingParent() {
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        when(assignments.findByMissionId("m")).thenReturn(List.of(assignment));
        var missionSchedule = UserSchedule.builder().scheduleType(UserScheduleType.MISSION).build();
        var leave = UserSchedule.builder().scheduleType(UserScheduleType.LEAVE).build();
        when(schedules.findByReferenceId("m")).thenReturn(List.of(missionSchedule, leave));
        service.deleteDraftMission("m");
        assertEquals(ChecklistAssignmentStatus.UNASSIGNED, assignment.getOrderChecklistItem().getAssignmentStatus());
        assertEquals(6, order.getExecutionPlanRevision());
        var sequence = inOrder(resources, assignments, schedules, locks, staff, devices, missions);
        verifyNoInteractions(resources);
        sequence.verify(assignments).deleteAll(List.of(assignment));
        sequence.verify(schedules).deleteAll(List.of(missionSchedule));
        sequence.verify(locks).deleteAll(List.of());
        sequence.verify(staff).deleteAll(List.of()); sequence.verify(devices).deleteAll(List.of());
        sequence.verify(missions).delete(mission);
    }

    @Test void cannotDeleteFinalizedOrExecutedMission() {
        error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT, () -> service.deleteDraftMission("m"));
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT); mission.setStatus(MissionStatus.IN_FLIGHT);
        error(ErrorCode.MISSION_STATUS_INVALID, () -> service.deleteDraftMission("m"));
        mission.setStatus(MissionStatus.PLANNING);
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(new MissionChecklistExecution()));
        error(ErrorCode.MISSION_STATUS_INVALID, () -> service.deleteDraftMission("m"));
        verify(missions, never()).delete(any(Mission.class)); verifyNoInteractions(resources);
    }
}
