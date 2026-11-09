package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.config.ActiveAccountFilter;
import com.ondemandmonitoring.config.SecurityConfig;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl;
import com.ondemandmonitoring.mission.service.impl.OrderMissionQueryService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.impl.OrderAuthorizationService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class,
        OrderMissionController.class, OrderMissionQueryService.class, OrderAuthorizationService.class,
        MissionAuthorizationServiceImpl.class, GlobalExceptionHandler.class, OrderMissionListHttpTest.Beans.class})
@WebAppConfiguration
class OrderMissionListHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired OrderRepository orders;
    @Autowired MissionRepository missions;
    @Autowired MissionStaffAssignmentRepository assignments;
    @Autowired MissionMapper mapper;
    MockMvc mvc;
    User caller;
    Order order;

    @BeforeEach void setup() {
        reset(resolver, orders, missions, assignments, mapper);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        caller = user("manager", RoleCode.MANAGER);
        order = Order.builder().customer(user("owner", RoleCode.CUSTOMER)).build(); order.setId("o");
        when(resolver.getCurrentUser()).thenAnswer(inv -> caller);
        when(orders.findById("o")).thenReturn(Optional.of(order));
        when(mapper.toResponse(any())).thenAnswer(inv -> {
            Mission mission = inv.getArgument(0); return MissionResponse.builder().id(mission.getId()).replacesMissionId(mission.getReplacesMissionId()).build();
        });
    }

    User user(String id, RoleCode role) {
        var user = User.builder().isActive(true).role(Role.builder().code(role).active(true).build()).build();
        user.setId(id); return user;
    }

    Mission child(String id) {
        var mission = new Mission(); mission.setId(id); mission.setOrder(order);
        when(missions.findById(id)).thenReturn(Optional.of(mission)); return mission;
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void managerReadsZeroOneOrMultipleChildren(int count) throws Exception {
        var children = java.util.stream.IntStream.range(0, count).mapToObj(i -> child("m" + i)).toList();
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(children);
        var result = mvc.perform(get("/api/orders/o/missions").with(jwt().authorities(() -> "ROLE_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(count));
        for (int i = 0; i < count; i++) result.andExpect(jsonPath("$.data[" + i + "].id").value("m" + i));
        verify(missions).findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o");
    }

    @Test void anonymousAndUnrelatedCallerCannotRead() throws Exception {
        mvc.perform(get("/api/orders/o/missions")).andExpect(status().isUnauthorized());
        caller = user("other", RoleCode.CUSTOMER);
        mvc.perform(get("/api/orders/o/missions").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isForbidden());
        caller = user("unassigned", RoleCode.STAFF);
        var children = List.of(child("m1"));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(children);
        mvc.perform(get("/api/orders/o/missions").with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
    }

    @Test void ownerReadsOrderChildrenWithoutObtainingStaffOperationalPermission() throws Exception {
        caller = user("owner", RoleCode.CUSTOMER);
        var children = List.of(child("m1"), child("m2"));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(children);
        mvc.perform(get("/api/orders/o/missions").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test void staffOrderContextDoesNotExposeSiblingDetails() throws Exception {
        caller = user("staff", RoleCode.STAFF);
        var children = List.of(child("m1"), child("m2"));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(children);
        when(assignments.existsByMissionIdAndStaffId("m1", "staff")).thenReturn(true);
        mvc.perform(get("/api/orders/o/missions").with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("m1"));
        verify(mapper, never()).toResponse(argThat(m -> m != null && "m2".equals(m.getId())));
    }

    @Test void missingOrderReturnsNotFoundForManager() throws Exception {
        mvc.perform(get("/api/orders/missing/missions").with(jwt().authorities(() -> "ROLE_MANAGER")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(missions, mapper);
    }

    @Test void replacementRelationsSurviveFreshOrderCollectionRead() throws Exception {
        var first=child("m1"); var second=child("m2"); var third=child("m3");
        second.setReplacesMissionId("m1"); third.setReplacesMissionId("m2");
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o")).thenReturn(List.of(first,second,third));
        mvc.perform(get("/api/orders/o/missions").with(jwt().authorities(() -> "ROLE_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].replacesMissionId").isEmpty())
                .andExpect(jsonPath("$.data[1].replacesMissionId").value("m1"))
                .andExpect(jsonPath("$.data[2].replacesMissionId").value("m2"));
    }

    @Configuration @EnableWebMvc
    static class Beans {
        @Bean com.ondemandmonitoring.mission.service.IMissionChecklistScopeService scope() { return com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope(); }
        @Bean IMissionService missionService() { return mock(IMissionService.class); }
        @Bean OrderRepository orders() { return mock(OrderRepository.class); }
        @Bean MissionRepository missions() { return mock(MissionRepository.class); }
        @Bean MissionStaffAssignmentRepository assignments() { return mock(MissionStaffAssignmentRepository.class); }
        @Bean MissionResultRepository results() { return mock(MissionResultRepository.class); }
        @Bean MissionMapper mapper() { return mock(MissionMapper.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean @Qualifier("cognitoAccessTokenDecoder") JwtDecoder decoder() { return mock(JwtDecoder.class); }
    }
}
