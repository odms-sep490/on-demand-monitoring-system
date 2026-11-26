package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceCycleResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceInspectionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceWorkSubmissionRequest;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceMaintenanceCycleEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceMaintenanceCycleRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates automated maintenance cycle detection, ticket creation (idempotent per device+policy+cycle),
 * maintainer work submission, post-maintenance inspection, checkpoint creation, and conditional unlock.
 */
@Service
@RequiredArgsConstructor
public class MaintenanceLifecycleService {

    private final DeviceRepository deviceRepository;
    private final MaintenanceTicketRepository ticketRepository;
    private final ResourcePolicyRepository policyRepository;
    private final ResourceMaintenanceCycleRepository cycleRepository;
    private final DroneHealthService droneHealthService;
    private final ResourceAlertService alertService;
    private final ResourceAuditService auditService;
    private final LifecycleJsonHelper jsonHelper;
    private final AuthenticatedUserResolver userResolver;

    @Transactional
    public Optional<MaintenanceCycleResponse> evaluateAndTriggerIfBreached(String deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.getDeviceModel() == null) {
            return Optional.empty();
        }
        ResourcePolicyEntity policy = policyRepository
                .findFirstByFamilyAndTargetIdAndActiveTrue("MAINTENANCE", device.getDeviceModel().getId())
                .orElse(null);
        if (policy == null) {
            return Optional.empty();
        }

        DroneHealthResponse health = droneHealthService.evaluateDevice(device, policy);
        if (!"MAINTENANCE_REQUIRED".equals(health.maintenanceStatus())) {
            if ("MAINTENANCE_DUE_SOON".equals(health.maintenanceStatus())) {
                alertService.openOrGet(
                        device.getId(),
                        null,
                        "MAINTENANCE_DUE_SOON",
                        "WARNING",
                        "Drone " + device.getName() + " sắp chạm ngưỡng bảo trì của chính sách " + policy.getName(),
                        "MAINT_SOON:" + device.getId() + ":" + policy.getId() + ":" + policy.getRevision());
            }
            return Optional.empty();
        }

        List<ResourceMaintenanceCycleEntity> existingCycles = cycleRepository
                .findByDeviceIdAndPolicyIdOrderByCycleDesc(device.getId(), policy.getId());

        Optional<ResourceMaintenanceCycleEntity> openCycle = existingCycles.stream()
                .filter(cycle -> cycle.getCheckpointAt() == null)
                .findFirst();
        if (openCycle.isPresent()) {
            return Optional.of(toResponse(openCycle.get()));
        }

        int nextCycleNumber = existingCycles.isEmpty() ? 1 : existingCycles.getFirst().getCycle() + 1;
        Optional<ResourceMaintenanceCycleEntity> duplicateCheck = cycleRepository
                .findByDeviceIdAndPolicyIdAndCycle(device.getId(), policy.getId(), nextCycleNumber);
        if (duplicateCheck.isPresent()) {
            return Optional.of(toResponse(duplicateCheck.get()));
        }

        Instant now = Instant.now();
        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setTicketCode("MNT-AUTO-" + device.getSerialNumber() + "-C" + nextCycleNumber + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        ticket.setDevice(device);
        ticket.setReportedBy("AUTOMATED_LIFECYCLE_POLICY");
        ticket.setIssueType("PREVENTIVE_MAINTENANCE");
        ticket.setSeverity("HIGH");
        ticket.setDescription("Tự động tạo theo chính sách bảo trì \"" + policy.getName()
                + "\" (chu kỳ #" + nextCycleNumber + "). Lý do: " + String.join(", ", health.reasons()));
        ticket.setStatus("OPEN");
        ticket.setOpenedAt(now);
        MaintenanceTicket savedTicket = ticketRepository.save(ticket);

        device.setStatus(DeviceStatus.MAINTENANCE);
        deviceRepository.save(device);

        ResourceMaintenanceCycleEntity cycle = new ResourceMaintenanceCycleEntity();
        cycle.setId(UUID.randomUUID());
        cycle.setDeviceId(device.getId());
        cycle.setPolicyId(policy.getId());
        cycle.setCycle(nextCycleNumber);
        cycle.setTicketId(savedTicket.getId());
        cycle.setDetectedAt(now);
        cycle.setDetectedSnapshotJson(jsonHelper.toJson(Map.of(
                "sinceMaintenanceSeconds", health.sinceMaintenanceSeconds() == null ? 0L : health.sinceMaintenanceSeconds(),
                "lifetimeActualSeconds", health.lifetimeActualSeconds() == null ? 0L : health.lifetimeActualSeconds(),
                "sinceMaintenanceCycles", health.sinceMaintenanceCycles(),
                "policyRevision", policy.getRevision(),
                "reasons", health.reasons())));
        ResourceMaintenanceCycleEntity savedCycle = cycleRepository.save(cycle);

        alertService.openOrGet(
                device.getId(),
                null,
                "MAINTENANCE_REQUIRED",
                "HIGH",
                "Drone " + device.getName() + " đã chạm ngưỡng bảo trì và tạo phiếu " + savedTicket.getTicketCode(),
                "MAINT_REQ:" + device.getId() + ":" + policy.getId() + ":" + nextCycleNumber);

        auditService.record(
                resolveActorOrSystem(),
                "MAINTENANCE_CYCLE_TRIGGERED",
                savedCycle.getId().toString(),
                null,
                Map.of(
                        "deviceId", device.getId(),
                        "policyId", policy.getId().toString(),
                        "cycle", nextCycleNumber,
                        "ticketId", savedTicket.getId()),
                "Automated maintenance threshold breach");

        return Optional.of(toResponse(savedCycle));
    }

    @Transactional
    public MaintenanceCycleResponse submitWork(UUID cycleId, MaintenanceWorkSubmissionRequest request) {
        ResourceMaintenanceCycleEntity cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance cycle not found: " + cycleId));
        if (cycle.getCheckpointAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Maintenance cycle already completed and checkpointed");
        }

        String actorId = userResolver.getCurrentUserId();
        Map<String, Object> before = Map.of(
                "submittedAt", String.valueOf(cycle.getSubmittedAt()),
                "workNotes", String.valueOf(cycle.getWorkNotes()));

        StringBuilder notesBuilder = new StringBuilder(request.workNotes().trim());
        if (request.checklistItems() != null && !request.checklistItems().isEmpty()) {
            notesBuilder.append("\nChecklist: ").append(String.join("; ", request.checklistItems()));
        }
        if (request.replacedComponents() != null && !request.replacedComponents().isEmpty()) {
            notesBuilder.append("\nReplaced components: ").append(String.join("; ", request.replacedComponents()));
        }

        cycle.setWorkNotes(notesBuilder.toString());
        cycle.setEvidenceReference(request.evidenceReference().trim());
        cycle.setCompletedBy(actorId);
        cycle.setSubmittedAt(Instant.now());
        ResourceMaintenanceCycleEntity saved = cycleRepository.save(cycle);

        if (cycle.getTicketId() != null) {
            ticketRepository.findById(cycle.getTicketId()).ifPresent(ticket -> {
                ticket.setStatus("RESOLVED");
                ticket.setResolutionNotes(saved.getWorkNotes());
                ticket.setResolvedAt(Instant.now());
                ticketRepository.save(ticket);
            });
        }

        auditService.record(
                actorId,
                "MAINTENANCE_WORK_SUBMITTED",
                saved.getId().toString(),
                before,
                Map.of("submittedAt", saved.getSubmittedAt().toString(), "evidenceReference", saved.getEvidenceReference()),
                "Maintainer submitted post-maintenance work and evidence");
        return toResponse(saved);
    }

    @Transactional
    public MaintenanceCycleResponse recordInspection(UUID cycleId, MaintenanceInspectionRequest request) {
        ResourceMaintenanceCycleEntity cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance cycle not found: " + cycleId));
        if (cycle.getSubmittedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Maintenance work must be submitted before post-maintenance inspection");
        }
        if (cycle.getCheckpointAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Maintenance cycle already checkpointed");
        }

        String actorId = userResolver.getCurrentUserId();
        Instant now = Instant.now();
        Map<String, Object> before = Map.of(
                "inspectionPassed", String.valueOf(cycle.getInspectionPassed()),
                "checkpointAt", String.valueOf(cycle.getCheckpointAt()));

        cycle.setInspectionPassed(request.passed());
        cycle.setInspectedBy(actorId);
        cycle.setInspectedAt(now);

        Device device = deviceRepository.findById(cycle.getDeviceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Device not found: " + cycle.getDeviceId()));

        if (Boolean.TRUE.equals(request.passed())) {
            DroneHealthResponse currentHealth = droneHealthService.evaluateDevice(device, null);
            long lifetimeSeconds = currentHealth.lifetimeActualSeconds() == null
                    ? 0L
                    : currentHealth.lifetimeActualSeconds();
            long lifetimeCycles = currentHealth.lifetimeCycles();

            cycle.setCheckpointAt(now);
            cycle.setCheckpointSeconds(lifetimeSeconds);
            cycle.setCheckpointCycles(lifetimeCycles);
            cycleRepository.save(cycle);

            if (cycle.getTicketId() != null) {
                ticketRepository.findById(cycle.getTicketId()).ifPresent(ticket -> {
                    ticket.setStatus("CLOSED");
                    ticket.setClosedAt(now);
                    ticketRepository.save(ticket);
                });
            }

            // Only restore AVAILABLE when no other blocking cycle or open ticket remains
            boolean stillHasOpenCycles = cycleRepository.existsByDeviceIdAndCheckpointAtIsNull(device.getId());
            boolean stillHasOpenTickets = ticketRepository.findAll().stream()
                    .anyMatch(ticket -> ticket.getDevice() != null
                            && device.getId().equals(ticket.getDevice().getId())
                            && !"CLOSED".equalsIgnoreCase(ticket.getStatus())
                            && !ticket.getId().equals(cycle.getTicketId()));

            if (!stillHasOpenCycles && !stillHasOpenTickets && device.getStatus() == DeviceStatus.MAINTENANCE) {
                device.setStatus(DeviceStatus.AVAILABLE);
                deviceRepository.save(device);
            }
        } else {
            cycleRepository.save(cycle);
            if (cycle.getTicketId() != null) {
                ticketRepository.findById(cycle.getTicketId()).ifPresent(ticket -> {
                    ticket.setStatus("IN_PROGRESS");
                    ticketRepository.save(ticket);
                });
            }
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("inspectionPassed", request.passed());
        after.put("checkpointAt", cycle.getCheckpointAt() == null ? null : cycle.getCheckpointAt().toString());
        after.put("checkpointSeconds", cycle.getCheckpointSeconds());
        after.put("deviceStatus", device.getStatus().name());

        auditService.record(
                actorId,
                Boolean.TRUE.equals(request.passed()) ? "MAINTENANCE_INSPECTION_PASSED" : "MAINTENANCE_INSPECTION_FAILED",
                cycle.getId().toString(),
                before,
                after,
                request.reason());

        return toResponse(cycle);
    }

    @Transactional(readOnly = true)
    public boolean requiresPostMaintenanceInspection(String ticketId) {
        return cycleRepository.findByTicketId(ticketId)
                .map(cycle -> cycle.getCheckpointAt() == null)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasBlockingCycleForDevice(String deviceId) {
        return cycleRepository.existsByDeviceIdAndCheckpointAtIsNull(deviceId);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceCycleResponse> listCycles(boolean openOnly) {
        List<ResourceMaintenanceCycleEntity> rows = openOnly
                ? cycleRepository.findByCheckpointAtIsNullOrderByDetectedAtDesc()
                : cycleRepository.findTop100ByOrderByDetectedAtDesc();
        return rows.stream().map(this::toResponse).toList();
    }

    private MaintenanceCycleResponse toResponse(ResourceMaintenanceCycleEntity cycle) {
        String deviceName = deviceRepository.findById(cycle.getDeviceId())
                .map(Device::getName)
                .orElse(cycle.getDeviceId());
        String policyName = policyRepository.findById(cycle.getPolicyId())
                .map(ResourcePolicyEntity::getName)
                .orElse(cycle.getPolicyId().toString());
        MaintenanceTicket ticket = cycle.getTicketId() == null
                ? null
                : ticketRepository.findById(cycle.getTicketId()).orElse(null);

        return new MaintenanceCycleResponse(
                cycle.getId(),
                cycle.getDeviceId(),
                deviceName,
                cycle.getPolicyId(),
                policyName,
                cycle.getCycle(),
                cycle.getTicketId(),
                ticket == null ? null : ticket.getTicketCode(),
                ticket == null ? null : ticket.getStatus(),
                cycle.getDetectedAt(),
                cycle.getWorkNotes(),
                cycle.getEvidenceReference(),
                cycle.getCompletedBy(),
                cycle.getSubmittedAt(),
                cycle.getInspectionPassed(),
                cycle.getInspectedBy(),
                cycle.getInspectedAt(),
                cycle.getCheckpointAt(),
                cycle.getCheckpointSeconds(),
                cycle.getCheckpointCycles());
    }

    private String resolveActorOrSystem() {
        try {
            String userId = userResolver.getCurrentUserId();
            return userId == null || userId.isBlank() ? "SYSTEM" : userId;
        } catch (RuntimeException ex) {
            return "SYSTEM";
        }
    }
}
