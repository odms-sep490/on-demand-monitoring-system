package com.ondemandmonitoring.mission.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IResourceReservationService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class MissionDraftCancellationCompatibilityTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void deletesDraftReworkOnFinalizedPlanAsAnAuditedCancellation(boolean internal) {
    var service = mock(MissionService.class, CALLS_REAL_METHODS);
    var missions = mock(MissionRepository.class);
    var orders = mock(OrderRepository.class);
    var order = new Order();
    order.setId("order");
    order.setOrderStatus(OrderStatus.IN_PROGRESS);
    order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
    var m = new Mission();
    m.setId("child");
    m.setOrder(order);
    m.setPurpose(MissionPurpose.REWORK);
    m.setStatus(MissionStatus.PLANNING);
    if (internal)
      m.setInspectionCorrection(
          new com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection());
    else m.setResultRevisionCaseId("case");
    ReflectionTestUtils.setField(service, "missionRepository", missions);
    ReflectionTestUtils.setField(service, "orderRepository", orders);
    ReflectionTestUtils.setField(
        service,
        "missionChecklistExecutionRepository",
        mock(MissionChecklistExecutionRepository.class));
    ReflectionTestUtils.setField(service, "resources", mock(IResourceReservationService.class));
    ReflectionTestUtils.setField(service,"userScheduleRepository",mock(com.ondemandmonitoring.userschedule.repository.UserScheduleRepository.class));
    ReflectionTestUtils.setField(
        service, "authenticatedUserResolver", mock(AuthenticatedUserResolver.class));
    ReflectionTestUtils.setField(
        service,
        "missionDeviceAssignmentRepository",
        mock(MissionDeviceAssignmentRepository.class));
    ReflectionTestUtils.setField(
        service, "missionStaffAssignmentRepository", mock(MissionStaffAssignmentRepository.class));
    ReflectionTestUtils.setField(
        service,
        "missionChecklistAssignmentRepository",
        mock(MissionChecklistAssignmentRepository.class));
    ReflectionTestUtils.setField(
        service, "orderChecklistItemRepository", mock(OrderChecklistItemRepository.class));
    when(missions.findOrderIdByMissionId("child")).thenReturn(Optional.of("order"));
    when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
    when(missions.findByIdForUpdate("child")).thenReturn(Optional.of(m));
    service.cancelDraftMission("child");
    assertThat(m.getStatus()).isEqualTo(MissionStatus.CANCELLED);
    assertThat(m.getCancellationAt()).isNotNull();
    verify(missions, never()).delete(any(Mission.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void completedOrderMayCancelOnlyFollowUpMissions(boolean rework) {
    var order = new Order();
    order.setId("order");
    order.setOrderStatus(OrderStatus.COMPLETED);
    order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
    var m = new Mission();
    m.setOrder(order);
    m.setId("child");
    m.setStatus(MissionStatus.PLANNING);
    m.setPurpose(rework ? MissionPurpose.REWORK : MissionPurpose.ORIGINAL);
    if (rework) m.setResultRevisionCaseId("case");
    Runnable check =
        () ->
            MissionCancellationGuard.requireAllowed(
                "order",
                order,
                m,
                0,
                mock(MissionResultRepository.class),
                mock(MissionRepository.class));
    if (rework) assertThatCode(check::run).doesNotThrowAnyException();
    else
      assertThatThrownBy(check::run)
          .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
  }
}
