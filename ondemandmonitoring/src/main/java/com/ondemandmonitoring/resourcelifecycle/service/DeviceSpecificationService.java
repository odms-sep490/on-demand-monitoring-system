package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.DeviceModel;
import com.ondemandmonitoring.device.repository.DeviceModelRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.SpecificationRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.SpecificationResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceSpecificationEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceSpecificationRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separates verified manufacturer specifications from approved operating specifications
 * per DeviceModel without inventing or hardcoding manufacturer numbers.
 */
@Service
@RequiredArgsConstructor
public class DeviceSpecificationService {

    private final DeviceModelRepository modelRepository;
    private final ResourceSpecificationRepository specRepository;
    private final ResourceAuditService auditService;
    private final LifecycleJsonHelper jsonHelper;
    private final AuthenticatedUserResolver userResolver;

    @Transactional(readOnly = true)
    public List<SpecificationResponse> listAllSpecifications() {
        return modelRepository.findAll().stream()
                .map(model -> toResponse(model, specRepository.findById(model.getId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SpecificationResponse getSpecification(String modelId) {
        DeviceModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DeviceModel not found: " + modelId));
        return toResponse(model, specRepository.findById(modelId).orElse(null));
    }

    @Transactional
    public SpecificationResponse upsertSpecification(String modelId, SpecificationRequest request) {
        DeviceModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DeviceModel not found: " + modelId));

        ResourceSpecificationEntity entity = specRepository.findById(modelId).orElse(null);
        if (entity != null && entity.getRevision() != request.expectedRevision()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Specification revision conflict: expected " + request.expectedRevision()
                            + " but current is " + entity.getRevision());
        }

        SpecificationResponse before = entity == null ? null : toResponse(model, entity);
        String actorId = userResolver.getCurrentUserId();
        Instant now = Instant.now();

        if (entity == null) {
            entity = new ResourceSpecificationEntity();
            entity.setModelId(modelId);
            entity.setRevision(1L);
        } else {
            entity.setRevision(entity.getRevision() + 1L);
        }

        entity.setManufacturerSpecsJson(jsonHelper.toJson(request.manufacturerSpecs()));
        entity.setOperatingSpecsJson(jsonHelper.toJson(request.operatingSpecs()));
        entity.setSourceReference(request.sourceReference().trim());
        entity.setUpdatedAt(now);
        if (request.verify()) {
            entity.setVerifiedBy(actorId);
            entity.setVerifiedAt(now);
        }

        ResourceSpecificationEntity saved = specRepository.save(entity);
        SpecificationResponse after = toResponse(model, saved);
        auditService.record(
                actorId,
                request.verify() ? "DEVICE_SPEC_VERIFIED" : "DEVICE_SPEC_UPDATED",
                modelId,
                before,
                after,
                request.sourceReference());
        return after;
    }

    private SpecificationResponse toResponse(DeviceModel model, ResourceSpecificationEntity entity) {
        if (entity == null) {
            return new SpecificationResponse(
                    model.getId(),
                    model.getModelName(),
                    model.getManufacturer(),
                    Map.of(),
                    Map.of(),
                    "",
                    null,
                    null,
                    0L,
                    null);
        }
        return new SpecificationResponse(
                model.getId(),
                model.getModelName(),
                model.getManufacturer(),
                jsonHelper.toMap(entity.getManufacturerSpecsJson()),
                jsonHelper.toMap(entity.getOperatingSpecsJson()),
                entity.getSourceReference(),
                entity.getVerifiedBy(),
                entity.getVerifiedAt(),
                entity.getRevision(),
                entity.getUpdatedAt());
    }
}
