package com.ondemandmonitoring.service.controller;

import com.ondemandmonitoring.config.ActiveAccountFilter;
import com.ondemandmonitoring.config.SecurityConfig;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import com.ondemandmonitoring.service.service.IServiceImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes = { SecurityConfig.class, ActiveAccountFilter.class,
        ServiceImageController.class, GlobalExceptionHandler.class, ServiceImageAuthorizationTest.Beans.class })
@WebAppConfiguration
class ServiceImageAuthorizationTest {
    @Autowired WebApplicationContext context;
    @Autowired IServiceImageService images;
    @Autowired AuthenticatedUserResolver resolver;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(images);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(images.upload(eq("s1"), any())).thenReturn(ServiceResponse.builder().id("s1").imageUrl("signed-url").build());
        when(images.remove("s1")).thenReturn(ServiceResponse.builder().id("s1").build());
    }

    @Test
    void anonymousCannotUploadOrRemove() throws Exception {
        mvc.perform(multipart("/api/services/s1/image").file(file()).with(request -> {
            request.setMethod("PUT"); return request;
        })).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/services/s1/image")).andExpect(status().isUnauthorized());
        verifyNoInteractions(images);
    }

    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void nonAdminsCannotUploadOrRemove(RoleCode role) throws Exception {
        role(role);
        mvc.perform(multipart("/api/services/s1/image").file(file()).with(request -> {
            request.setMethod("PUT"); return request;
        }).with(jwt().authorities(() -> "ROLE_" + role.name())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/services/s1/image").with(jwt().authorities(() -> "ROLE_" + role.name())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }

    @Test
    void adminCanUploadAndRemoveUsingBearerToken() throws Exception {
        role(RoleCode.ADMIN);
        mvc.perform(multipart("/api/services/s1/image").file(file()).with(request -> {
            request.setMethod("PUT"); return request;
        }).with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageUrl").value("signed-url"));
        mvc.perform(delete("/api/services/s1/image").with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void missingFileReturns400() throws Exception {
        role(RoleCode.ADMIN);
        mvc.perform(multipart("/api/services/s1/image").with(request -> {
            request.setMethod("PUT"); return request;
        }).with(jwt().authorities(() -> "ROLE_ADMIN"))).andExpect(status().isBadRequest());
    }

    private void role(RoleCode code) {
        when(resolver.getCurrentUser()).thenReturn(User.builder().isActive(true)
                .role(Role.builder().code(code).active(true).build()).build());
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "image.png", "image/png", new byte[]{1});
    }

    @Configuration
    @EnableWebMvc
    static class Beans {
        @Bean IServiceImageService images() { return mock(IServiceImageService.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean @Qualifier("cognitoAccessTokenDecoder")
        JwtDecoder decoder() { return mock(JwtDecoder.class); }
    }
}
