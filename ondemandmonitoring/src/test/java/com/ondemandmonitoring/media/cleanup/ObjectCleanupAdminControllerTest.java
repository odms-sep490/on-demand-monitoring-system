package com.ondemandmonitoring.media.cleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

class ObjectCleanupAdminControllerTest {
    AnnotationConfigApplicationContext context;
    ObjectCleanupAdminController controller;
    ObjectCleanupOutbox outbox;

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ObjectCleanupOutbox outbox() {
            return mock(ObjectCleanupOutbox.class);
        }

        @Bean
        ObjectCleanupAdminController controller(ObjectCleanupOutbox outbox) {
            return new ObjectCleanupAdminController(outbox);
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(ObjectCleanupAdminController.class);
        outbox = context.getBean(ObjectCleanupOutbox.class);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    void signIn(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "operator", null, AuthorityUtils.createAuthorityList(role)));
    }

    @Test
    void onlyAdminCanInspectKeysOrRetryDeletion() {
        for (String role : List.of("ROLE_CUSTOMER", "ROLE_STAFF", "ROLE_MANAGER")) {
            signIn(role);
            assertThrows(AccessDeniedException.class, () -> controller.list("DEAD", 0, 20));
            assertThrows(AccessDeniedException.class, () -> controller.retry(UUID.randomUUID()));
        }
        verifyNoInteractions(outbox);
    }

    @Test
    void adminUsesBoundedListingAndExistingDeadTaskOnly() {
        signIn("ROLE_ADMIN");
        when(outbox.list("DEAD", 0, 20)).thenReturn(new ObjectCleanupOutbox.TaskPage(0, List.of()));
        assertNotNull(controller.list("DEAD", 0, 20));
        when(outbox.list("bad", 0, 20)).thenThrow(new IllegalArgumentException());
        assertEquals(
                400,
                assertThrows(ResponseStatusException.class, () -> controller.list("bad", 0, 20))
                        .getStatusCode()
                        .value());
        UUID id = UUID.randomUUID();
        assertEquals(
                409,
                assertThrows(ResponseStatusException.class, () -> controller.retry(id))
                        .getStatusCode()
                        .value());
        when(outbox.retry(id.toString())).thenReturn(true);
        assertNotNull(controller.retry(id));
    }
}
