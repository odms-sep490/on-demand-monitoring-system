package com.ondemandmonitoring.audit.service;

import com.ondemandmonitoring.audit.domain.AdminAuditLog;
import com.ondemandmonitoring.audit.dto.AuditEntryResponse;
import com.ondemandmonitoring.audit.dto.AuditLogFilter;
import com.ondemandmonitoring.audit.dto.AuditLogPageResponse;
import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.audit.repository.AdminAuditLogRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Records administrative changes and serves the Admin audit log page. */
@Service
@RequiredArgsConstructor
public class AdminAuditLogService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 100;
    private static final String SYSTEM_ACTOR = "system";

    private final AdminAuditLogRepository repository;
    private final AuthenticatedUserResolver currentUser;

    @Transactional
    public void record(AuditAction action, String entityType, String entityId,
            Map<String, ?> before, Map<String, ?> after) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setCreatedAt(Instant.now());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId == null || entityId.isBlank() ? "-" : entityId);
        entry.setBefore(copy(before));
        entry.setAfter(copy(after));
        applyActor(entry);
        applyRequest(entry);
        repository.save(entry);
    }

    /** Inserts and flushes the audit envelope before the business mutation starts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditLog begin(AuditAction action, String entityType, String entityId,
            Map<String, ?> before) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setCreatedAt(Instant.now());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId == null || entityId.isBlank() ? "-" : entityId);
        entry.setBefore(copy(before));
        applyActor(entry);
        applyRequest(entry);
        return repository.saveAndFlush(entry);
    }

    /** Completes the operation snapshot before the shared transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(AdminAuditLog entry, String entityId, Map<String, ?> after) {
        if (entityId != null && !entityId.isBlank() && !"-".equals(entityId)) {
            entry.setEntityId(entityId);
        }
        entry.setAfter(copy(after));
        repository.saveAndFlush(entry);
    }

    @Transactional(readOnly = true)
    public AuditLogPageResponse search(AuditLogFilter filter, Integer page, Integer limit) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Page<AdminAuditLog> result = repository.findAll(specification(filter),
                PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<AuditEntryResponse> items = result.getContent().stream().map(AdminAuditLogService::toResponse).toList();
        return new AuditLogPageResponse(items, result.getTotalElements(), safePage, safeLimit);
    }

    static Specification<AdminAuditLog> specification(AuditLogFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter != null) {
                if (hasText(filter.actorId())) predicates.add(cb.equal(root.get("actorId"), filter.actorId()));
                if (hasText(filter.actorQuery())) {
                    String actorPattern = "%" + filter.actorQuery().trim().toLowerCase(Locale.ROOT) + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.<String>get("actorId")), actorPattern),
                            cb.like(cb.lower(root.<String>get("actorName")), actorPattern)));
                }
                if (filter.action() != null) predicates.add(cb.equal(root.get("action"), filter.action()));
                if (hasText(filter.entityType())) predicates.add(cb.equal(root.get("entityType"), filter.entityType()));
                if (filter.from() != null)
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOfDay(filter.from())));
                if (filter.to() != null)
                    predicates.add(cb.lessThan(root.get("createdAt"), startOfDay(filter.to().plusDays(1))));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    private void applyActor(AdminAuditLog entry) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            entry.setActorId(SYSTEM_ACTOR);
            entry.setActorName(SYSTEM_ACTOR);
            return;
        }

        User user = currentUser.getCurrentUser();
        entry.setActorId(user.getId());
        entry.setActorName(hasText(user.getFullName()) ? user.getFullName() : user.getEmail());
        if (!hasText(entry.getActorName())) entry.setActorName(entry.getActorId());
    }

    private static void applyRequest(AdminAuditLog entry) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return;
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        entry.setIp(truncate(ip, 64));
        entry.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
    }

    private static AuditEntryResponse toResponse(AdminAuditLog e) {
        return new AuditEntryResponse(e.getId(), e.getCreatedAt(), e.getActorId(), e.getActorName(), e.getAction(),
                e.getEntityType(), e.getEntityId(), e.getIp() == null ? "" : e.getIp(),
                e.getUserAgent() == null ? "" : e.getUserAgent(), e.getBefore(), e.getAfter());
    }

    private static Map<String, Object> copy(Map<String, ?> values) {
        return values == null ? null : new LinkedHashMap<>(values);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
