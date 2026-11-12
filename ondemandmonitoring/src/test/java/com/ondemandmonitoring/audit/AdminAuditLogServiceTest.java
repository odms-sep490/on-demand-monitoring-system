package com.ondemandmonitoring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.audit.domain.AdminAuditLog;
import com.ondemandmonitoring.audit.dto.AuditLogFilter;
import com.ondemandmonitoring.audit.dto.AuditLogPageResponse;
import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.audit.repository.AdminAuditLogRepository;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AdminAuditLogServiceTest {
    private final AdminAuditLogRepository repository = mock(AdminAuditLogRepository.class);
    private final AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    private final AdminAuditLogService service = new AdminAuditLogService(repository, resolver);

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordCapturesActorIpAndUserAgent() {
        User admin = new User();
        admin.setId("admin-1");
        admin.setFullName("Admin One");
        when(resolver.getCurrentUser()).thenReturn(admin);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "10.0.0.5, 172.16.0.1");
        request.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditAction.STATUS_CHANGE, "account", "user-9",
                Map.of("status", "ACTIVE"), Map.of("status", "INACTIVE"));

        ArgumentCaptor<AdminAuditLog> saved = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(repository).save(saved.capture());
        AdminAuditLog entry = saved.getValue();
        assertThat(entry.getActorId()).isEqualTo("admin-1");
        assertThat(entry.getActorName()).isEqualTo("Admin One");
        assertThat(entry.getIp()).isEqualTo("10.0.0.5");
        assertThat(entry.getUserAgent()).isEqualTo("JUnit");
        assertThat(entry.getBefore()).containsEntry("status", "ACTIVE");
        assertThat(entry.getAfter()).containsEntry("status", "INACTIVE");
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void recordFallsBackToSystemActorWithoutAuthentication() {
        when(resolver.getCurrentUser()).thenThrow(new ApiException(ErrorCode.UNAUTHORIZED));

        service.record(AuditAction.CREATE, "pricing_policy", null, null, Map.of("version", 2));

        ArgumentCaptor<AdminAuditLog> saved = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getActorId()).isEqualTo("system");
        assertThat(saved.getValue().getEntityId()).isEqualTo("-");
        assertThat(saved.getValue().getIp()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchUsesOneBasedPagingClampsLimitAndSortsNewestFirst() {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setId("a-1");
        entry.setCreatedAt(Instant.parse("2026-09-20T01:30:00Z"));
        entry.setAction(AuditAction.UPDATE);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(entry), inv.getArgument(1), 420));

        AuditLogPageResponse page = service.search(
                new AuditLogFilter(null, AuditAction.UPDATE, "account", null, null), 3, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.limit()).isEqualTo(100);
        assertThat(page.total()).isEqualTo(420);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("a-1");
            assertThat(item.ip()).isEmpty();
        });
    }

    @Test
    void searchExcludesTechnicalAndSystemGeneratedUserStatusEntries() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> Page.empty(inv.getArgument(1)));

        service.search(null, null, null);

        ArgumentCaptor<Specification<AdminAuditLog>> specification = ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specification.capture(), any(Pageable.class));

        @SuppressWarnings("unchecked")
        Root<AdminAuditLog> root = mock(Root.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> entityType = mock(Path.class);
        Path<String> actorId = mock(Path.class);
        Path<AuditAction> action = mock(Path.class);
        Predicate predicate = mock(Predicate.class);

        when(root.<String>get("entityType")).thenReturn(entityType);
        when(root.<String>get("actorId")).thenReturn(actorId);
        when(root.<AuditAction>get("action")).thenReturn(action);
        when(entityType.in(anyCollection())).thenReturn(predicate);
        when(criteriaBuilder.not(any(Predicate.class))).thenReturn(predicate);
        when(criteriaBuilder.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(predicate);

        assertThat(specification.getValue().toPredicate(root, null, criteriaBuilder)).isNotNull();

        verify(entityType).in(java.util.Set.of("refresh_token_cookie", "service_default_price"));
        verify(criteriaBuilder).equal(entityType, "user");
        verify(criteriaBuilder).equal(actorId, "system");
        verify(criteriaBuilder).equal(action, AuditAction.STATUS_CHANGE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDefaultsToFirstPageOfTen() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> Page.empty(inv.getArgument(1)));

        AuditLogPageResponse page = service.search(null, null, null);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.items()).isEmpty();
    }
}
