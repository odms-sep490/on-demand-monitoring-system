package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.CustomerMissionHistoryMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.impl.CustomerMissionHistoryServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerMissionHistoryServiceTest {
    final MissionRepository missions = mock(MissionRepository.class);
    final AuthenticatedUserResolver user = mock(AuthenticatedUserResolver.class);
    final CustomerMissionHistoryMapper mapper =
            org.mapstruct.factory.Mappers.getMapper(CustomerMissionHistoryMapper.class);
    final ICustomerMissionHistoryService service =
            new CustomerMissionHistoryServiceImpl(missions, user, mapper);

    @BeforeEach
    void setup() {
        when(user.getCurrentUserId()).thenReturn("customer");
    }

    @Test
    void listsOnlyTerminalMissionsBelongingToCurrentCustomer() {
        List<MissionStatus> statuses = List.of(MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED);
        when(missions.findByOrder_Customer_IdAndStatusIn(eq("customer"), eq(statuses), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(mission(MissionStatus.COMPLETED)),
                        call.getArgument(2), 1));
        var result = service.listHistory(0, 20);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getOrderId()).isEqualTo("order");
        assertThat(result.getItems().getFirst().getOrderTitle()).isEqualTo("Survey");
        assertThat(result.getItems().getFirst().getStatus()).isEqualTo(MissionStatus.COMPLETED);
    }

    @Test
    void returnsOwnedHistoryDetail() {
        when(missions.findByIdAndOrder_Customer_Id("mission", "customer"))
                .thenReturn(Optional.of(mission(MissionStatus.COMPLETED)));
        assertThat(service.getHistory("mission").getId()).isEqualTo("mission");
    }

    @Test
    void deniesMissingOrOtherCustomersMission() {
        when(missions.findByIdAndOrder_Customer_Id("other", "customer")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getHistory("other")).isInstanceOf(ApiException.class);
        verify(missions, never()).findById(anyString());
    }

    @Test
    void activeMissionIsNotHistory() {
        when(missions.findByIdAndOrder_Customer_Id("mission", "customer"))
                .thenReturn(Optional.of(mission(MissionStatus.IN_PROGRESS)));
        assertThatThrownBy(() -> service.getHistory("mission")).isInstanceOf(ApiException.class);
    }

    Mission mission(MissionStatus status) {
        Order order = new Order();
        order.setId("order");
        order.setTitle("Survey");
        Mission mission = new Mission();
        mission.setId("mission");
        mission.setMissionCode("MS-1");
        mission.setStatus(status);
        mission.setOrder(order);
        return mission;
    }
}
