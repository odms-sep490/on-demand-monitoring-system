package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.ImpactedResource;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyActionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyImpactPreview;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.PolicyVersionResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.StaffWorkloadResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyVersionEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyVersionRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages versioned MAINTENANCE, WORKFORCE, and ALLOCATION policies with
 * optimistic revision locking, impact preview, activation, and full audit history.
 */
@Service
@RequiredArgsConstructor
public class ResourcePolicyService {

    private final ResourcePolicyRepository policyRepository;
    private final ResourcePolicyVersionRepository versionRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final DroneHealthService droneHealthService;
    private final WorkforceWorkloadService workforceWorkloadService;
    private final MaintenanceLifecycleService maintenanceLifecycleService;
    private final ResourceAuditService auditService;
    private final LifecycleJsonHelper jsonHelper;
    private final AuthenticatedUserResolver userResolver;

    @Transactional
    public PolicyResponse createPolicy(PolicyRequest request) {
        validatePolicyRules(request.family(), request.rules());

        String actorId = userResolver.getCurrentUserId();
        Instant now = Instant.now();

        ResourcePolicyEntity entity = new ResourcePolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setFamily(request.family());
        entity.setTargetId(request.targetId().trim());
        entity.setName(request.name().trim());
        entity.setRevision(1L);
        entity.setActive(false); // New policies default to inactive until previewed and activated
        entity.setEffectiveAt(request.effectiveAt());
        entity.setRulesJson(jsonHelper.toJson(request.rules()));
        entity.setReason(request.reason().trim());
        entity.setCreatedBy(actorId);
        entity.setUpdatedAt(now);

        ResourcePolicyEntity saved = policyRepository.save(entity);
        saveVersionSnapshot(saved, actorId, "CREATED");
        auditService.record(actorId, "POLICY_CREATED", saved.getId().toString(), null, toResponse(saved), request.reason());
        return toResponse(saved);
    }

    @Transactional
    public PolicyResponse updatePolicy(UUID policyId, PolicyRequest request) {
        ResourcePolicyEntity entity = policyRepository.findById(policyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found: " + policyId));
        assertExpectedRevision(entity, request.expectedRevision());
        validatePolicyRules(request.family(), request.rules());

        PolicyResponse before = toResponse(entity);
        String actorId = userResolver.getCurrentUserId();

        entity.setName(request.name().trim());
        entity.setEffectiveAt(request.effectiveAt());
        entity.setRulesJson(jsonHelper.toJson(request.rules()));
        entity.setReason(request.reason().trim());
        entity.setRevision(entity.getRevision() + 1L);
        entity.setUpdatedAt(Instant.now());

        ResourcePolicyEntity saved = policyRepository.save(entity);
        saveVersionSnapshot(saved, actorId, "UPDATED");
        auditService.record(actorId, "POLICY_UPDATED", saved.getId().toString(), before, toResponse(saved), request.reason());

        if (saved.isActive() && "MAINTENANCE".equals(saved.getFamily())) {
            reevaluateDevicesForModel(saved.getTargetId());
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PolicyImpactPreview previewImpact(UUID policyId) {
        ResourcePolicyEntity candidate = policyRepository.findById(policyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found: " + policyId));

        List<ImpactedResource> impacted = new ArrayList<>();
        int newlyBlocked = 0;
        int newlyWarning = 0;
        int evaluated = 0;

        if ("MAINTENANCE".equals(candidate.getFamily())) {
            List<Device> devices = deviceRepository.findAll().stream()
                    .filter(device -> device.getDeviceModel() != null
                            && candidate.getTargetId().equals(device.getDeviceModel().getId()))
                    .toList();
            evaluated = devices.size();
            for (Device device : devices) {
                DroneHealthResponse before = droneHealthService.evaluateDevice(device, null);
                DroneHealthResponse after = droneHealthService.evaluateDevice(device, candidate);
                if (after.blocked() && !before.blocked()) {
                    newlyBlocked++;
                } else if ("MAINTENANCE_DUE_SOON".equals(after.maintenanceStatus())
                        && !"MAINTENANCE_DUE_SOON".equals(before.maintenanceStatus())) {
                    newlyWarning++;
                }
                impacted.add(new ImpactedResource(
                        device.getId(),
                        device.getName(),
                        before.maintenanceStatus(),
                        after.maintenanceStatus(),
                        after.reasons()));
            }
        } else if ("WORKFORCE".equals(candidate.getFamily())) {
            List<User> staffList = userRepository
                    .findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF);
            evaluated = staffList.size();
            for (User staff : staffList) {
                if (!"ALL".equals(candidate.getTargetId())
                        && !"STAFF".equals(candidate.getTargetId())
                        && !staff.getId().equals(candidate.getTargetId())) {
                    continue;
                }
                StaffWorkloadResponse before = workforceWorkloadService.evaluateStaff(staff, null, null, null, null);
                StaffWorkloadResponse after = workforceWorkloadService.evaluateStaff(staff, null, null, null, candidate);
                if (after.blocked() && !before.blocked()) {
                    newlyBlocked++;
                } else if ("WARNING".equals(after.workloadStatus()) && !"WARNING".equals(before.workloadStatus())) {
                    newlyWarning++;
                }
                impacted.add(new ImpactedResource(
                        staff.getId(),
                        staff.getFullName(),
                        before.workloadStatus(),
                        after.workloadStatus(),
                        after.blocked() ? after.blockingReasons() : after.warningReasons()));
            }
        }

        return new PolicyImpactPreview(
                candidate.getId(),
                candidate.getFamily(),
                candidate.getTargetId(),
                evaluated,
                newlyBlocked,
                newlyWarning,
                impacted);
    }

    @Transactional
    public PolicyResponse activatePolicy(UUID policyId, PolicyActionRequest request) {
        ResourcePolicyEntity entity = policyRepository.findById(policyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found: " + policyId));
        assertExpectedRevision(entity, request.expectedRevision());

        String actorId = userResolver.getCurrentUserId();
        PolicyResponse before = toResponse(entity);

        policyRepository.findFirstByFamilyAndTargetIdAndActiveTrue(entity.getFamily(), entity.getTargetId())
                .filter(existing -> !existing.getId().equals(entity.getId()))
                .ifPresent(existing -> {
                    existing.setActive(false);
                    existing.setRevision(existing.getRevision() + 1L);
                    existing.setUpdatedAt(Instant.now());
                    policyRepository.save(existing);
                    saveVersionSnapshot(existing, actorId, "SUPERSEDED_BY_ACTIVATION");
                });

        entity.setActive(true);
        entity.setRevision(entity.getRevision() + 1L);
        entity.setReason(request.reason().trim());
        entity.setUpdatedAt(Instant.now());
        ResourcePolicyEntity saved = policyRepository.save(entity);
        saveVersionSnapshot(saved, actorId, "ACTIVATED");
        auditService.record(actorId, "POLICY_ACTIVATED", saved.getId().toString(), before, toResponse(saved), request.reason());

        if ("MAINTENANCE".equals(saved.getFamily())) {
            reevaluateDevicesForModel(saved.getTargetId());
        }
        return toResponse(saved);
    }

    @Transactional
    public PolicyResponse deactivatePolicy(UUID policyId, PolicyActionRequest request) {
        ResourcePolicyEntity entity = policyRepository.findById(policyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found: " + policyId));
        assertExpectedRevision(entity, request.expectedRevision());

        String actorId = userResolver.getCurrentUserId();
        PolicyResponse before = toResponse(entity);

        entity.setActive(false);
        entity.setRevision(entity.getRevision() + 1L);
        entity.setReason(request.reason().trim());
        entity.setUpdatedAt(Instant.now());
        ResourcePolicyEntity saved = policyRepository.save(entity);
        saveVersionSnapshot(saved, actorId, "DEACTIVATED");
        auditService.record(actorId, "POLICY_DEACTIVATED", saved.getId().toString(), before, toResponse(saved), request.reason());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> listPolicies(String family) {
        List<ResourcePolicyEntity> rows = (family == null || family.isBlank())
                ? policyRepository.findAllByOrderByUpdatedAtDesc()
                : policyRepository.findByFamilyOrderByUpdatedAtDesc(family.trim().toUpperCase());
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PolicyVersionResponse> listVersions(UUID policyId) {
        return versionRepository.findByPolicyIdOrderByRevisionDesc(policyId).stream()
                .map(version -> new PolicyVersionResponse(
                        version.getPolicyId(),
                        version.getRevision(),
                        jsonHelper.toMap(version.getSnapshotJson()),
                        version.getActorId(),
                        version.getAction(),
                        version.getCreatedAt()))
                .toList();
    }

    private void reevaluateDevicesForModel(String modelId) {
        deviceRepository.findAll().stream()
                .filter(device -> device.getDeviceModel() != null && modelId.equals(device.getDeviceModel().getId()))
                .forEach(device -> maintenanceLifecycleService.evaluateAndTriggerIfBreached(device.getId()));
    }

    private void assertExpectedRevision(ResourcePolicyEntity entity, long expectedRevision) {
        if (entity.getRevision() != expectedRevision) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Policy revision conflict: expected " + expectedRevision + " but current is " + entity.getRevision());
        }
    }

    private void validatePolicyRules(String family, Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Policy rules cannot be empty");
        }
        if ("MAINTENANCE".equals(family)) {
            Integer monitor = jsonHelper.readInt(rules, "monitorPercent");
            Integer warning = jsonHelper.readInt(rules, "warningPercent");
            int m = monitor == null ? 60 : monitor;
            int w = warning == null ? 80 : warning;
            if (m < 0 || w <= m || w >= 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid monitor/warning percentages");
            }
            Long maxSeconds = jsonHelper.readLong(rules, "maxFlightSeconds");
            Double maxHours = jsonHelper.readDouble(rules, "maxFlightHours");
            Integer maxCycles = jsonHelper.readInt(rules, "maxFlightCycles");
            Integer maxDays = jsonHelper.readInt(rules, "maxCalendarDays");
            if (maxSeconds == null && maxHours == null && maxCycles == null && maxDays == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Maintenance policy must specify at least one threshold");
            }
            if ((maxSeconds != null && maxSeconds <= 0L)
                    || (maxHours != null && maxHours <= 0.0)
                    || (maxCycles != null && maxCycles <= 0)
                    || (maxDays != null && maxDays <= 0)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Maintenance policy thresholds must be positive");
            }
        } else if ("WORKFORCE".equals(family)) {
            Integer warning = jsonHelper.readInt(rules, "warningPercent");
            if (warning != null && (warning <= 0 || warning >= 100)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Workforce warningPercent must be between 1 and 99");
            }
        }
    }

    private void saveVersionSnapshot(ResourcePolicyEntity policy, String actorId, String action) {
        ResourcePolicyVersionEntity version = new ResourcePolicyVersionEntity();
        version.setPolicyId(policy.getId());
        version.setRevision(policy.getRevision());
        version.setSnapshotJson(jsonHelper.toJson(toResponse(policy)));
        version.setActorId(actorId);
        version.setAction(action);
        version.setCreatedAt(Instant.now());
        versionRepository.save(version);
    }

    private PolicyResponse toResponse(ResourcePolicyEntity entity) {
        return new PolicyResponse(
                entity.getId(),
                entity.getFamily(),
                entity.getTargetId(),
                entity.getName(),
                entity.getRevision(),
                entity.isActive(),
                entity.getEffectiveAt(),
                jsonHelper.toMap(entity.getRulesJson()),
                entity.getReason(),
                entity.getCreatedBy(),
                entity.getUpdatedAt());
    }
}
