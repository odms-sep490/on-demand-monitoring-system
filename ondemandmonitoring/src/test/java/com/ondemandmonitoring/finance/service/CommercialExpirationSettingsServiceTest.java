package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.finance.controller.AdminCommercialExpirationSettingsController;
import com.ondemandmonitoring.finance.domain.CommercialExpirationSettings;
import com.ondemandmonitoring.finance.domain.CommercialExpirationSettingsAudit;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsRequest;
import com.ondemandmonitoring.finance.repository.CommercialExpirationSettingsAuditRepository;
import com.ondemandmonitoring.finance.repository.CommercialExpirationSettingsRepository;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

class CommercialExpirationSettingsServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    CommercialExpirationSettingsRepository settings = mock(CommercialExpirationSettingsRepository.class);
    CommercialExpirationSettingsAuditRepository audits = mock(CommercialExpirationSettingsAuditRepository.class);
    UserIdentityRepository identities = mock(UserIdentityRepository.class);
    CommercialExpirationSettingsService service = new CommercialExpirationSettingsService(settings, audits,
            identities, Clock.fixed(NOW, ZoneOffset.UTC));
    CommercialExpirationSettings row;

    @BeforeEach
    void setUp() {
        row = new CommercialExpirationSettings();
        row.setId(CommercialExpirationSettings.DEFAULT_ID);
        row.setQuotationAcceptanceTimeoutHours(48);
        row.setDepositPaymentTimeoutHours(24);
        row.setUpdatedAt(Instant.EPOCH);
        row.setUpdatedBy("system");
        when(settings.findByIdForUpdate(CommercialExpirationSettings.DEFAULT_ID)).thenReturn(Optional.of(row));
        when(settings.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void changesBothTimeoutsAndAuditsEachFieldWithActorAndTime() {
        var result = service.update(new CommercialExpirationSettingsRequest(72, 12), "admin-123");

        assertThat(result.quotationAcceptanceTimeoutHours()).isEqualTo(72);
        assertThat(result.depositPaymentTimeoutHours()).isEqualTo(12);
        assertThat(result.updatedBy()).isEqualTo("admin-123");
        assertThat(result.updatedAt()).isEqualTo(NOW);
        verify(audits).saveAll(argThat(events -> {
            List<CommercialExpirationSettingsAudit> list = (List<CommercialExpirationSettingsAudit>) events;
            return list.size() == 2 && list.stream().allMatch(event -> event.getActor().equals("admin-123")
                    && event.getChangedAt().equals(NOW))
                    && list.stream().anyMatch(event -> event.getSettingName().equals("quotationAcceptanceTimeoutHours")
                    && event.getPreviousValue() == 48 && event.getNewValue() == 72)
                    && list.stream().anyMatch(event -> event.getSettingName().equals("depositPaymentTimeoutHours")
                    && event.getPreviousValue() == 24 && event.getNewValue() == 12);
        }));
    }

    @Test
    void returnsSuggestedDefaultsWhenTheSingletonHasNotYetBeenSeeded() {
        when(settings.findById(CommercialExpirationSettings.DEFAULT_ID)).thenReturn(Optional.empty());

        var result = service.current();

        assertThat(result.quotationAcceptanceTimeoutHours()).isEqualTo(48);
        assertThat(result.depositPaymentTimeoutHours()).isEqualTo(24);
    }

    @Test
    void quotationTimeoutCanBeUpdatedIndependently() {
        var result = service.update(new CommercialExpirationSettingsRequest(36, 24), "admin-123");

        assertThat(result.quotationAcceptanceTimeoutHours()).isEqualTo(36);
        assertThat(result.depositPaymentTimeoutHours()).isEqualTo(24);
        verify(audits).saveAll(argThat(events -> {
            List<CommercialExpirationSettingsAudit> list = (List<CommercialExpirationSettingsAudit>) events;
            return list.size() == 1 && list.getFirst().getSettingName().equals("quotationAcceptanceTimeoutHours");
        }));
    }

    @Test
    void depositTimeoutCanBeUpdatedIndependently() {
        var result = service.update(new CommercialExpirationSettingsRequest(48, 18), "admin-123");

        assertThat(result.quotationAcceptanceTimeoutHours()).isEqualTo(48);
        assertThat(result.depositPaymentTimeoutHours()).isEqualTo(18);
        verify(audits).saveAll(argThat(events -> {
            List<CommercialExpirationSettingsAudit> list = (List<CommercialExpirationSettingsAudit>) events;
            return list.size() == 1 && list.getFirst().getSettingName().equals("depositPaymentTimeoutHours");
        }));
    }

    @Test
    void unchangedSettingsDoNotCreateAuditEntries() {
        service.update(new CommercialExpirationSettingsRequest(48, 24), "admin-123");
        verify(settings, never()).save(any());
        verify(audits, never()).saveAll(any());
    }

    @Test
    void rejectsNonPositiveOrOverOneYearTimeouts() {
        assertThatThrownBy(() -> service.update(new CommercialExpirationSettingsRequest(0, 24), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(new CommercialExpirationSettingsRequest(-1, 24), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(new CommercialExpirationSettingsRequest(48, 8761), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(settings, never()).findByIdForUpdate(any());
    }

    @Test
    void resolvesLegacyCognitoActorIdsToUserNamesInAuditHistory() {
        String cognitoSub = "550e8400-e29b-41d4-a716-446655440000";
        User actor = new User();
        actor.setFullName("Nguyễn Thị An");
        UserIdentity identity = new UserIdentity();
        identity.setCognitoSub(cognitoSub);
        identity.setUser(actor);
        CommercialExpirationSettingsAudit audit = new CommercialExpirationSettingsAudit();
        audit.setId("audit-1");
        audit.setSettingName("quotationAcceptanceTimeoutHours");
        audit.setPreviousValue(48);
        audit.setNewValue(72);
        audit.setActor(cognitoSub);
        audit.setChangedAt(NOW);
        when(audits.findAllByOrderByChangedAtDesc(any())).thenReturn(List.of(audit));
        when(identities.findAllByCognitoSubIn(List.of(cognitoSub))).thenReturn(List.of(identity));

        var result = service.recentAudit(50);

        assertThat(result).singleElement()
                .extracting(item -> item.actor())
                .isEqualTo("Nguyễn Thị An");
    }

    @Test
    void everyAdminConfigurationRouteRequiresTheAdminRole() {
        PreAuthorize authorization = AdminCommercialExpirationSettingsController.class.getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void recordsTheAuthenticatedUsersDisplayName() {
        CommercialExpirationSettingsService settingsService = mock(CommercialExpirationSettingsService.class);
        AuthenticatedUserResolver currentUser = mock(AuthenticatedUserResolver.class);
        User actor = new User();
        actor.setFullName("Nguyễn Thị An");
        when(currentUser.getCurrentUser()).thenReturn(actor);
        AdminCommercialExpirationSettingsController controller =
                new AdminCommercialExpirationSettingsController(settingsService, currentUser);
        CommercialExpirationSettingsRequest request = new CommercialExpirationSettingsRequest(72, 12);

        controller.update(request);

        verify(settingsService).update(request, "Nguyễn Thị An");
    }

    @Test
    void methodSecurityRejectsAManagerAndAllowsAnAdmin() {
        try (var context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class)) {
            AdminCommercialExpirationSettingsController controller =
                    context.getBean(AdminCommercialExpirationSettingsController.class);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "manager", "", AuthorityUtils.createAuthorityList("ROLE_MANAGER")));
            assertThatThrownBy(controller::current).isInstanceOf(AccessDeniedException.class);

            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin", "", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
            assertThat(controller.current()).isNotNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        CommercialExpirationSettingsService expirationSettingsService() {
            return mock(CommercialExpirationSettingsService.class);
        }

        @Bean
        AuthenticatedUserResolver currentUser() {
            return mock(AuthenticatedUserResolver.class);
        }

        @Bean
        AdminCommercialExpirationSettingsController adminExpirationController(
                CommercialExpirationSettingsService service, AuthenticatedUserResolver currentUser) {
            return new AdminCommercialExpirationSettingsController(service, currentUser);
        }
    }
}
