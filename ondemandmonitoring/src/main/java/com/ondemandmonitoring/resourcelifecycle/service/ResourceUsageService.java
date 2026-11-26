package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.UsageRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.UsageResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.VerificationRequest;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages verified manual and telemetry usage records with strict idempotency,
 * interval validation, superseding audit trail, and automatic maintenance re-evaluation.
 */
@Service
@RequiredArgsConstructor
public class ResourceUsageService {

    private final ResourceUsageRecordRepository usageRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final MaintenanceLifecycleService maintenanceLifecycleService;
    private final ResourceAuditService auditService;
    private final AuthenticatedUserResolver userResolver;

    @Transactional
    public UsageResponse recordUsage(UsageRequest request) {
        if (!request.startedAt().isBefore(request.endedAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "startedAt must be strictly before endedAt");
        }
        long windowSeconds = Duration.between(request.startedAt(), request.endedAt()).getSeconds();
        if (request.seconds() > windowSeconds) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Recorded seconds (" + request.seconds() + ") cannot exceed interval length (" + windowSeconds + "s)");
        }

        Optional<ResourceUsageRecordEntity> existing = usageRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        validateResourceExists(request.resourceKind(), request.resourceId());

        if (request.supersedes() != null) {
            ResourceUsageRecordEntity superseded = usageRepository.findById(request.supersedes())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Superseded usage record not found"));
            superseded.setStatus("SUPERSEDED");
            usageRepository.save(superseded);
        }

        String actorId = userResolver.getCurrentUserId();
        ResourceUsageRecordEntity entity = new ResourceUsageRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setResourceId(request.resourceId());
        entity.setResourceKind(request.resourceKind());
        entity.setMissionId(request.missionId());
        entity.setAssignmentId(request.assignmentId());
        entity.setRole(request.role());
        entity.setStartedAt(request.startedAt());
        entity.setEndedAt(request.endedAt());
        entity.setActualSeconds(request.seconds());
        entity.setEstimatedSeconds(0L);
        entity.setCycles(request.cycles());
        entity.setDistanceM(request.distanceM());
        entity.setSource("VERIFIED_MANUAL");
        entity.setStatus("PENDING");
        entity.setEvidenceReference(request.evidenceReference().trim());
        entity.setReason(request.reason().trim());
        entity.setIdempotencyKey(request.idempotencyKey().trim());
        entity.setCreatedBy(actorId);
        entity.setCreatedAt(Instant.now());
        entity.setSupersedes(request.supersedes());

        ResourceUsageRecordEntity saved = usageRepository.save(entity);
        auditService.record(
                actorId,
                "USAGE_RECORD_CREATED",
                saved.getId().toString(),
                null,
                Map.of(
                        "resourceKind", saved.getResourceKind(),
                        "resourceId", saved.getResourceId(),
                        "actualSeconds", saved.getActualSeconds(),
                        "source", saved.getSource(),
                        "status", saved.getStatus()),
                saved.getReason());
        return toResponse(saved);
    }

    @Transactional
    public UsageResponse verifyUsage(UUID usageId, VerificationRequest request) {
        ResourceUsageRecordEntity record = usageRepository.findById(usageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usage record not found: " + usageId));
        if (!"PENDING".equals(record.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Usage record is already in status: " + record.getStatus());
        }

        String actorId = userResolver.getCurrentUserId();
        Map<String, Object> before = Map.of("status", record.getStatus());

        record.setStatus(Boolean.TRUE.equals(request.approved()) ? "VERIFIED" : "SUPERSEDED");
        record.setVerifiedBy(actorId);
        record.setVerifiedAt(Instant.now());
        ResourceUsageRecordEntity saved = usageRepository.save(record);

        auditService.record(
                actorId,
                Boolean.TRUE.equals(request.approved()) ? "USAGE_RECORD_VERIFIED" : "USAGE_RECORD_REJECTED",
                saved.getId().toString(),
                before,
                Map.of(
                        "status", saved.getStatus(),
                        "resourceKind", saved.getResourceKind(),
                        "resourceId", saved.getResourceId(),
                        "actualSeconds", saved.getActualSeconds()),
                request.reason());

        if ("VERIFIED".equals(saved.getStatus()) && "DEVICE".equals(saved.getResourceKind())) {
            maintenanceLifecycleService.evaluateAndTriggerIfBreached(saved.getResourceId());
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UsageResponse> listUsageRecords(String resourceKind, String resourceId, String status) {
        List<ResourceUsageRecordEntity> records;
        if (resourceKind != null && !resourceKind.isBlank() && resourceId != null && !resourceId.isBlank()) {
            records = usageRepository.findByResourceKindAndResourceIdOrderByStartedAtDesc(
                    resourceKind.trim().toUpperCase(), resourceId.trim());
        } else if (status != null && !status.isBlank()) {
            records = usageRepository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        } else {
            records = usageRepository.findTop100ByOrderByCreatedAtDesc();
        }
        return records.stream().map(this::toResponse).toList();
    }

    private void validateResourceExists(String resourceKind, String resourceId) {
        if ("DEVICE".equals(resourceKind)) {
            if (!deviceRepository.existsById(resourceId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Device not found: " + resourceId);
            }
        } else if ("STAFF".equals(resourceKind)) {
            if (!userRepository.existsById(resourceId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Staff user not found: " + resourceId);
            }
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported resourceKind: " + resourceKind);
        }
    }

    private UsageResponse toResponse(ResourceUsageRecordEntity entity) {
        return new UsageResponse(
                entity.getId(),
                entity.getResourceId(),
                entity.getResourceKind(),
                entity.getMissionId(),
                entity.getAssignmentId(),
                entity.getRole(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getActualSeconds(),
                entity.getEstimatedSeconds(),
                entity.getCycles(),
                entity.getDistanceM(),
                entity.getSource(),
                entity.getStatus(),
                entity.getEvidenceReference(),
                entity.getReason(),
                entity.getIdempotencyKey(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getVerifiedBy(),
                entity.getVerifiedAt(),
                entity.getSupersedes());
    }
}
