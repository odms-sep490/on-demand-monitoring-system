package com.ondemandmonitoring.mission.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class MissionWorkspaceSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Beans {
        @Bean(name = "missionAuthorizationService") IMissionAuthorizationService authorization() {
            return mock(IMissionAuthorizationService.class);
        }
        @Bean NamedParameterJdbcTemplate db() { return mock(NamedParameterJdbcTemplate.class); }
        @Bean AuthenticatedUserResolver users() { return mock(AuthenticatedUserResolver.class); }
        @Bean MissionWorkspaceVersionService versions(NamedParameterJdbcTemplate db, AuthenticatedUserResolver users) {
            return new MissionWorkspaceVersionService(db, users);
        }
    }

    @Test void unauthenticatedAndUnassignedViewersCannotProbeMissionVersions() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            var service = context.getBean(MissionWorkspaceVersionService.class);
            var db = context.getBean(NamedParameterJdbcTemplate.class);
            var authorization = context.getBean(IMissionAuthorizationService.class);
            SecurityContextHolder.clearContext();
            assertThrows(AuthenticationCredentialsNotFoundException.class, () -> service.get("private-mission"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("unrelated-staff", null, List.of()));
            when(authorization.canViewMission("private-mission")).thenReturn(false);
            assertThrows(AccessDeniedException.class, () -> service.get("private-mission"));
            verifyNoInteractions(db, context.getBean(AuthenticatedUserResolver.class));
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void notifierUsesOnlyUserDestinationsAndBrokerFailureDoesNotEscapeCommittedCommand() {
        var db = mock(NamedParameterJdbcTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        when(db.queryForList(eq(MissionWorkspaceNotifier.RECIPIENTS), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("subject-1", "subject-2"));
        doThrow(new IllegalStateException("broker unavailable")).when(messaging)
                .convertAndSendToUser(eq("subject-1"), eq("/queue/mission-events"), any(Object.class));
        assertDoesNotThrow(() -> new MissionWorkspaceNotifier(db, messaging).notify(
                new MissionWorkspaceEvents.Changed("m", Set.of("checklist"), Set.of())));
        verify(messaging).convertAndSendToUser(eq("subject-2"), eq("/queue/mission-events"),
                argThat((Object payload) -> payload instanceof MissionWorkspaceEvents.Message message
                        && message.missionId().equals("m") && message.components().equals(List.of("checklist"))));
    }
}
