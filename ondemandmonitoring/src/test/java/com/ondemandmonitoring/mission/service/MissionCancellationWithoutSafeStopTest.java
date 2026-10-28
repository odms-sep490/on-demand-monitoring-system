package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.MissionCancelRequest;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionCancellationServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionCancellationWithoutSafeStopTest {
    @Test void cancellationDoesNotConsultSafeStopOrUnsafeResourceChecks() {
        var orders = mock(OrderRepository.class); var missions = mock(MissionRepository.class);
        var results = mock(MissionResultRepository.class); var actor = mock(AuthenticatedUserResolver.class);
        var service = new MissionCancellationServiceImpl(orders, missions, results, actor);
        var order = new Order(); order.setId("o"); order.setOrderStatus(OrderStatus.APPROVED);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED); order.setExecutionPlanRevision(2);
        var mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.IN_FLIGHT);
        when(missions.findOrderIdByMissionId("m")).thenReturn(Optional.of("o"));
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(orders.findById("o")).thenReturn(Optional.of(order));
        when(actor.getCurrentUserId()).thenReturn("manager");
        var response = service.cancel("m", new MissionCancelRequest(2L, "  Cancel requested  "));
        assertEquals(MissionStatus.CANCELLED, response.status());
        assertEquals(MissionResourcePrepareStatus.CANCELLED, mission.getResourcePrepareStatus());
        assertEquals(MissionResourcePrepareStatus.CANCELLED, response.resourcePrepareStatus());
        assertEquals("Cancel requested", mission.getCancellationReason());
        assertEquals(3, order.getExecutionPlanRevision());
        verify(missions, never()).hasUnsafeCancellationResources(anyString());
        assertNull(mission.getSafeStopStatus()); assertNull(mission.getSafeStopRequestedAt());
    }
}
