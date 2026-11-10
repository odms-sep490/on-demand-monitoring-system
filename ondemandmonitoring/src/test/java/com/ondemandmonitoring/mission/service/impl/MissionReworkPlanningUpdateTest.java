package com.ondemandmonitoring.mission.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ResultRevisionCase;
import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.MissionUpdateRequest;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.MissionChecklistExecutionRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IResourceReservationService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MissionReworkPlanningUpdateTest {
    private final MissionService service = mock(MissionService.class, CALLS_REAL_METHODS);
    private final MissionRepository missions = mock(MissionRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ResultRevisionCaseRepository cases = mock(ResultRevisionCaseRepository.class);
    private final MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
    private final IResourceReservationService resources = mock(IResourceReservationService.class);
    private final MissionMapper mapper = mock(MissionMapper.class);
    private final Order order = new Order();
    private final Mission mission = new Mission();
    private final ResultRevisionCase revisionCase = new ResultRevisionCase();

    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "missionRepository", missions);
        ReflectionTestUtils.setField(service, "orderRepository", orders);
        ReflectionTestUtils.setField(service, "resultRevisionCases", cases);
        ReflectionTestUtils.setField(service, "missionChecklistExecutionRepository", executions);
        ReflectionTestUtils.setField(service, "resources", resources);
        ReflectionTestUtils.setField(service, "missionMapper", mapper);
        order.setId("order");
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1);
        order.setPreferredDateTo(LocalDate.of(2026, 11, 9));
        mission.setId("mission");
        mission.setOrder(order);
        mission.setPurpose(MissionPurpose.REWORK);
        mission.setResultRevisionCaseId("case");
        mission.setStatus(MissionStatus.PLANNING);
        revisionCase.setOrder(order);
        revisionCase.setActive(true);
        revisionCase.setStatus(ResultRevisionCaseStatus.OPEN);
        when(missions.findOrderIdByMissionId("mission")).thenReturn(Optional.of("order"));
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(missions.findByIdForUpdate("mission")).thenReturn(Optional.of(mission));
        when(cases.findById("case")).thenReturn(Optional.of(revisionCase));
        when(executions.findOrderedByMissionId("mission")).thenReturn(List.of());
        when(missions.save(mission)).thenReturn(mission);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = OrderStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
    void editsReworkScheduleThroughTheSharedCommandWithoutChangingOriginalPreferences(OrderStatus status) {
        order.setOrderStatus(status);
        var request = schedule();
        service.updateMission("mission", request);
        assertThat(mission.getScheduledStartAt()).isEqualTo(request.getScheduledStartAt());
        assertThat(order.getPreferredDateTo()).isEqualTo(LocalDate.of(2026, 11, 9));
        verify(resources).revalidateSchedule(mission, request.getScheduledStartAt(), request.getScheduledEndAt());
        verify(missions).save(mission);
    }

    @Test
    void cannotExpandTheApprovedReworkScopeWhileEditingTheSchedule() {
        var request = schedule();
        request.setChecklistIds(List.of("unapproved-item"));
        assertThatThrownBy(() -> service.updateMission("mission", request))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(resources);
    }

    @Test
    void aClosedCaseCannotChangeItsReworkSchedule() {
        revisionCase.setActive(false);
        assertThatThrownBy(() -> service.updateMission("mission", schedule()))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_DELIVERY_STATE);
        verifyNoInteractions(resources);
    }

    @Test
    void originalMissionsOnAFinalizedCompletedOrderRemainLocked() {
        mission.setPurpose(MissionPurpose.ORIGINAL);
        assertThatThrownBy(() -> service.updateMission("mission", schedule()))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.MISSION_STATUS_INVALID);
        verifyNoInteractions(resources);
    }

    private MissionUpdateRequest schedule() {
        var request = new MissionUpdateRequest();
        request.setScheduledStartAt(Instant.parse("2099-11-11T03:00:00Z"));
        request.setScheduledEndAt(Instant.parse("2099-11-11T04:00:00Z"));
        return request;
    }
}
