package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.AlertActionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.AlertResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceHealthAlertEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceHealthAlertRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages resource lifecycle health alerts.
 * Resolving an alert NEVER clears underlying maintenance or workforce blocking conditions.
 */
@Service
@RequiredArgsConstructor
public class ResourceAlertService {

    private final ResourceHealthAlertRepository alertRepository;
    private final ResourceAuditService auditService;
    private final AuthenticatedUserResolver userResolver;

    @Transactional
    public ResourceHealthAlertEntity openOrGet(
            String resourceId,
            String missionId,
            String type,
            String severity,
            String description,
            String dedupKey) {
        return alertRepository.findByDedupKey(dedupKey).orElseGet(() -> {
            Instant now = Instant.now();
            ResourceHealthAlertEntity alert = new ResourceHealthAlertEntity();
            alert.setId(UUID.randomUUID());
            alert.setResourceId(resourceId);
            alert.setMissionId(missionId);
            alert.setType(type);
            alert.setSeverity(severity);
            alert.setDescription(description);
            alert.setDedupKey(dedupKey);
            alert.setStatus("OPEN");
            alert.setDetectedAt(now);
            alert.setUpdatedAt(now);
            return alertRepository.save(alert);
        });
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> listAlerts(String status) {
        List<ResourceHealthAlertEntity> rows = (status == null || status.isBlank())
                ? alertRepository.findTop100ByOrderByDetectedAtDesc()
                : alertRepository.findByStatusOrderByDetectedAtDesc(status.trim().toUpperCase());
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> listAlertsForResource(String resourceId) {
        return alertRepository.findByResourceIdOrderByDetectedAtDesc(resourceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AlertResponse handleAction(UUID alertId, AlertActionRequest request) {
        ResourceHealthAlertEntity alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Alert not found: " + alertId));
        String actorId = userResolver.getCurrentUserId();
        Map<String, Object> before = Map.of("status", alert.getStatus());

        if ("ACKNOWLEDGE".equalsIgnoreCase(request.action())) {
            alert.setStatus("ACKNOWLEDGED");
            alert.setAcknowledgedBy(actorId);
        } else if ("RESOLVE".equalsIgnoreCase(request.action())) {
            alert.setStatus("RESOLVED");
            alert.setResolvedBy(actorId);
            alert.setResolutionNote(request.reason().trim());
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported alert action: " + request.action());
        }
        alert.setUpdatedAt(Instant.now());
        ResourceHealthAlertEntity saved = alertRepository.save(alert);

        auditService.record(
                actorId,
                "ALERT_" + saved.getStatus(),
                saved.getId().toString(),
                before,
                Map.of("status", saved.getStatus(), "resourceId", saved.getResourceId()),
                request.reason());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public long countOpenAlerts() {
        return alertRepository.countByStatus("OPEN");
    }

    private AlertResponse toResponse(ResourceHealthAlertEntity entity) {
        return new AlertResponse(
                entity.getId(),
                entity.getResourceId(),
                entity.getMissionId(),
                entity.getType(),
                entity.getSeverity(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getDetectedAt(),
                entity.getAcknowledgedBy(),
                entity.getResolvedBy(),
                entity.getResolutionNote(),
                entity.getUpdatedAt());
    }
}
