package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.controller.OperatorMissionMediaController;
import com.ondemandmonitoring.media.dto.response.MediaViewingResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatorOriginalViewAuthorizationTest {
    private AnnotationConfigApplicationContext context;
    private OperatorMissionMediaController controller;
    private IOperatorMissionMediaService service;

    @BeforeEach void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        controller = context.getBean(OperatorMissionMediaController.class);
        service = context.getBean(IOperatorMissionMediaService.class);
    }

    @AfterEach void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test void customerCannotReachInternalOriginalService() {
        signIn("ROLE_CUSTOMER");
        assertThatThrownBy(() -> controller.originalView("mission", "media"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void internalActorsRetainExactMissionAndMediaScope() {
        for (String role : new String[]{"ROLE_STAFF", "ROLE_MANAGER", "ROLE_ADMIN"}) {
            signIn(role);
            when(service.createOriginalView("mission", "media"))
                    .thenReturn(new MediaViewingResponse("media", "original", Instant.now(), 10));
            assertThat(controller.originalView("mission", "media")).isNotNull();
        }
        verify(service, times(3)).createOriginalView("mission", "media");
        verifyNoMoreInteractions(service);
    }

    private void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-actor", null, AuthorityUtils.createAuthorityList(role)));
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean IOperatorMissionMediaService mediaService() { return mock(IOperatorMissionMediaService.class); }
        @Bean OperatorMissionMediaController mediaController(IOperatorMissionMediaService service) {
            return new OperatorMissionMediaController(service);
        }
    }
}
