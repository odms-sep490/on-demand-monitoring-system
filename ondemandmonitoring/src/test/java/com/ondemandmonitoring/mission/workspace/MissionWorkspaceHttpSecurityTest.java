package com.ondemandmonitoring.mission.workspace;

import com.ondemandmonitoring.config.ActiveAccountFilter;
import com.ondemandmonitoring.config.SecurityConfig;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.*;
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

@SpringJUnitConfig(classes={SecurityConfig.class, ActiveAccountFilter.class,
        MissionWorkspaceController.class, MissionWorkspaceVersionService.class, MissionWorkspaceHttpSecurityTest.Beans.class})
@WebAppConfiguration
class MissionWorkspaceHttpSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired NamedParameterJdbcTemplate db;
    @Autowired IMissionAuthorizationService authorization;
    @Autowired AuthenticatedUserResolver users;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(db, authorization, users);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var user = User.builder().isActive(true).role(Role.builder().active(true).code(RoleCode.STAFF).build()).build();
        user.setId("staff"); when(users.getCurrentUser()).thenReturn(user);
    }

    @Test void unauthenticatedRequestIsRejectedBeforeDatabase() throws Exception {
        mvc.perform(get("/api/missions/m/workspace-version")).andExpect(status().isUnauthorized());
        verifyNoInteractions(db, authorization);
    }

    @Test void methodSecurityRejectsForbiddenMissionBeforeDatabase() throws Exception {
        when(authorization.canViewMission("m")).thenReturn(false);
        mvc.perform(get("/api/missions/m/workspace-version").with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isForbidden());
        verify(authorization).canViewMission("m"); verifyNoInteractions(db);
    }

    @Test void authorizedRequestReturnsOpaqueTokensWithNoStore() throws Exception {
        when(authorization.canViewMission("m")).thenReturn(true);
        when(db.queryForMap(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class)))
                .thenReturn(Map.of("checks", false, "reviews", false), Map.of());
        mvc.perform(get("/api/missions/m/workspace-version").with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.missionId").value("m"))
                .andExpect(jsonPath("$.checklist").value(org.hamcrest.Matchers.matchesPattern("[a-f0-9]{64}")));
    }

    @Configuration @EnableWebMvc
    static class Beans {
        @Bean NamedParameterJdbcTemplate db() { return mock(NamedParameterJdbcTemplate.class); }
        @Bean AuthenticatedUserResolver users() { return mock(AuthenticatedUserResolver.class); }
        @Bean JwtDecoder cognitoAccessTokenDecoder() { return mock(JwtDecoder.class); }
        @Bean("missionAuthorizationService") IMissionAuthorizationService authorization() { return mock(IMissionAuthorizationService.class); }
    }
}
