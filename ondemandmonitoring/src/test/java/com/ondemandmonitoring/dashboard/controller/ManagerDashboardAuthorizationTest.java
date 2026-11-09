package com.ondemandmonitoring.dashboard.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.config.ActiveAccountFilter;
import com.ondemandmonitoring.config.SecurityConfig;
import com.ondemandmonitoring.dashboard.dto.ManagerDashboardResponse;
import com.ondemandmonitoring.dashboard.service.ManagerDashboardService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class,
        ManagerDashboardController.class, ManagerDashboardAuthorizationTest.Beans.class})
@WebAppConfiguration
class ManagerDashboardAuthorizationTest {
    @Autowired WebApplicationContext context;
    @Autowired ManagerDashboardService dashboardService;
    @Autowired AuthenticatedUserResolver resolver;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(dashboardService, resolver);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(dashboardService.getDashboard(anyString(), nullable(java.time.LocalDate.class),
                nullable(java.time.LocalDate.class))).thenReturn(emptyDashboard());
    }

    @Test
    void anonymousCannotAccessDashboard() throws Exception {
        mvc.perform(get("/api/manager/dashboard")).andExpect(status().isUnauthorized());
        verifyNoInteractions(dashboardService);
    }

    @Test
    void customerAndOperatorCannotAccessDashboard() throws Exception {
        for (RoleCode role : List.of(RoleCode.CUSTOMER, RoleCode.STAFF)) {
            role(role);
            mvc.perform(get("/api/manager/dashboard")
                            .with(jwt().authorities(() -> "ROLE_" + role.name())))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(dashboardService);
    }

    @Test
    void managerCanAccessDashboard() throws Exception {
        role(RoleCode.MANAGER);
        mvc.perform(get("/api/manager/dashboard").with(jwt().authorities(() -> "ROLE_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.pendingOrderCount").value(0));
    }

    private void role(RoleCode role) {
        when(resolver.getCurrentUser()).thenReturn(User.builder().isActive(true)
                .role(Role.builder().code(role).active(true).build()).build());
    }

    private ManagerDashboardResponse emptyDashboard() {
        return new ManagerDashboardResponse(
                new ManagerDashboardResponse.Summary(0, java.math.BigDecimal.ZERO, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(),
                new ManagerDashboardResponse.PriceAdjustmentSummary(0, 0), List.of(), List.of(),
                List.of(), List.of(),
                new ManagerDashboardResponse.PricingPipeline(0, 0, 0, 0, 0),
                new ManagerDashboardResponse.MissionOverview(0, 0, 0, 0, 0, 0),
                List.of(), List.of(), Instant.parse("2026-10-22T00:00:00Z"));
    }

    @Configuration
    @EnableWebMvc
    static class Beans {
        @Bean ManagerDashboardService dashboardService() { return mock(ManagerDashboardService.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean @Qualifier("cognitoAccessTokenDecoder") JwtDecoder decoder() { return mock(JwtDecoder.class); }
    }
}
