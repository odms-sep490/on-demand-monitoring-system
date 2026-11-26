package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.AuditLogResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceLifecycleAuditEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceLifecycleAuditRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceAuditService {

    private final ResourceLifecycleAuditRepository auditRepository;
    private final LifecycleJsonHelper jsonHelper;

    @Transactional
    public void record(
            String actorId,
            String action,
            String entityId,
            Object beforeState,
            Object afterState,
            String reason) {
        ResourceLifecycleAuditEntity entity = new ResourceLifecycleAuditEntity();
        entity.setId(UUID.randomUUID());
        entity.setActorId(actorId);
        entity.setAction(action);
        entity.setEntityId(entityId);
        entity.setBeforeJson(beforeState == null ? null : jsonHelper.toJson(beforeState));
        entity.setAfterJson(jsonHelper.toJson(afterState));
        entity.setReason(reason == null || reason.isBlank() ? action : reason.trim());
        entity.setCreatedAt(Instant.now());
        auditRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> recentAudits() {
        return auditRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> auditsForEntity(String entityId) {
        return auditRepository.findByEntityIdOrderByCreatedAtDesc(entityId).stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditLogResponse toResponse(ResourceLifecycleAuditEntity entity) {
        return new AuditLogResponse(
                entity.getId(),
                entity.getActorId(),
                entity.getAction(),
                entity.getEntityId(),
                jsonHelper.toMap(entity.getBeforeJson()),
                jsonHelper.toMap(entity.getAfterJson()),
                entity.getReason(),
                entity.getCreatedAt());
    }
}
