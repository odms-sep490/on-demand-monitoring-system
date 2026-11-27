package com.ondemandmonitoring.config;

import com.ondemandmonitoring.ai.controller.AdminChecklistImageAiSettingsController;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService.Status;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class, GlobalExceptionHandler.class,
        AdminChecklistImageAiSettingsController.class, ChecklistImageAiSettingsHttpTest.Beans.class})
@WebAppConfiguration
class ChecklistImageAiSettingsHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ChecklistImageAiSettingsService settings;
    @Autowired AuthenticatedUserResolver resolver;
    MockMvc mvc;
    private static final String PATH = "/api/admin/config/checklist-image-ai";

    @BeforeEach void setup() {
        reset(settings, resolver);
        var user = User.builder().isActive(true)
                .role(Role.builder().code(RoleCode.ADMIN).active(true).build()).build();
        user.setId("admin");
        when(resolver.getCurrentUser()).thenReturn(user);
        when(resolver.getCurrentUserId()).thenReturn("admin");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void onlyAdminsCanReadAndWriteSettings() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String role : new String[]{"STAFF", "MANAGER", "CUSTOMER"}) {
            mvc.perform(get(PATH).with(jwt().authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isForbidden());
            mvc.perform(put(PATH).with(jwt().authorities(() -> "ROLE_" + role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"expectedVersion\":0}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(settings);
    }

    @Test void validatesVersionAndReturnsTheSavedSwitch() throws Exception {
        mvc.perform(put(PATH).with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        when(settings.update(any(), eq("admin")))
                .thenReturn(new Status(false, true, false, 1L, Instant.EPOCH, "admin"));
        mvc.perform(put(PATH).with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Configuration @EnableWebMvc
    static class Beans {
        @Bean JwtDecoder cognitoAccessTokenDecoder() { return mock(JwtDecoder.class); }
        @Bean ChecklistImageAiSettingsService settings() { return mock(ChecklistImageAiSettingsService.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
    }
}
