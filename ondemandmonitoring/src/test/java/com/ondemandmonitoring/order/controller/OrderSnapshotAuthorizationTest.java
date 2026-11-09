package com.ondemandmonitoring.order.controller;
import com.ondemandmonitoring.config.ActiveAccountFilter;
import com.ondemandmonitoring.config.SecurityConfig;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.dto.response.OrderChecklistItemResponse;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.order.service.impl.OrderAuthorizationService;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class, OrderController.class,
        OrderAuthorizationService.class, GlobalExceptionHandler.class, OrderSnapshotAuthorizationTest.Beans.class})
@WebAppConfiguration
class OrderSnapshotAuthorizationTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired IOrderService service;
    @Autowired OrderRepository orders;
    MockMvc mvc;
    @BeforeEach void setup() {
        reset(resolver, service, orders);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var owner = user("owner", RoleCode.CUSTOMER);
        when(orders.findById("o1")).thenReturn(Optional.of(Order.builder().customer(owner).build()));
        var item = new com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse();
        item.setId("stable"); item.setContent("Historical");
        when(service.getOrderById("o1")).thenReturn(OrderCreateResponse.builder().id("o1")
                .checklistItems(List.of(item)).build());
    }
    User user(String id, RoleCode role) {
        var user = User.builder().isActive(true).role(Role.builder().code(role).active(true).build()).build();
        user.setId(id); return user;
    }
    @Test void ownerReadsSnapshotButOtherCustomerCannot() throws Exception {
        when(resolver.getCurrentUser()).thenReturn(user("owner", RoleCode.CUSTOMER));
        mvc.perform(get("/api/orders/o1").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.checklistItems[0].id").value("stable"));
        clearInvocations(service);
        when(resolver.getCurrentUser()).thenReturn(user("other", RoleCode.CUSTOMER));
        mvc.perform(get("/api/orders/o1").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void anonymousCannotReadOrCreate() throws Exception {
        mvc.perform(get("/api/orders/o1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void nonCustomerCannotCreateSnapshotOrder(RoleCode role) throws Exception {
        when(resolver.getCurrentUser()).thenReturn(user("staff", role));
        mvc.perform(post("/api/orders").contentType("application/json").content(validBody())
                .with(jwt().authorities(() -> "ROLE_" + role.name()))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void customerCreatesWithOptionalChecklistPayload() throws Exception {
        when(resolver.getCurrentUser()).thenReturn(user("owner", RoleCode.CUSTOMER));
        mvc.perform(post("/api/orders").contentType("application/json").content(validBody())
                .with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isCreated());
        verify(service).createOrder(argThat(request -> request.getChecklistItems().getFirst()
                .getContentOverride().equals("Custom requirement")));
    }
    @Test void noSnapshotMutationApiExists() throws Exception {
        when(resolver.getCurrentUser()).thenReturn(user("owner", RoleCode.CUSTOMER));
        mvc.perform(put("/api/orders/o1").contentType("application/json").content("{}")
                .with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
    String validBody() {
        return """
                {"title":"Order","serviceId":"s1","dataRetentionDays":90,"termsAccepted":true,"monitoringRelationship":"OWNER","monitoringAuthorizationConfirmed":true,"longitude":106.7,"latitude":10.7,
                 "coverageArea":{"type":"Polygon"},"preferredDateFrom":"%s",
                 "preferredDateTo":"%s","preferredTimeId":"t1",
                 "deliverables":[{"deliverableTypeId":"d1","requirement":{}}],
                 "checklistItems":[{"contentOverride":"Custom requirement"}]}
                """.formatted(java.time.LocalDate.now().plusDays(7), java.time.LocalDate.now().plusDays(8));
    }
    @Configuration @EnableWebMvc
    static class Beans {
        @Bean com.ondemandmonitoring.mission.repository.MissionRepository missionRepository() {
            return mock(com.ondemandmonitoring.mission.repository.MissionRepository.class);
        }
        @Bean IOrderService service() { return mock(IOrderService.class); }
        @Bean OrderRepository orders() { return mock(OrderRepository.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean IMissionAuthorizationService missions() { return mock(IMissionAuthorizationService.class); }
        @Bean @Qualifier("cognitoAccessTokenDecoder") JwtDecoder decoder() { return mock(JwtDecoder.class); }
    }
}
