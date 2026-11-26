package com.ondemandmonitoring.resourcelifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceModel;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.EligibilityResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceCycleResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceInspectionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.MaintenanceWorkSubmissionRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.UsageRequest;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.VerificationRequest;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceHealthAlertEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceLifecycleAuditEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceMaintenanceCycleEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceHealthAlertRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceLifecycleAuditRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceMaintenanceCycleRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import com.ondemandmonitoring.resourcelifecycle.service.DroneHealthService;
import com.ondemandmonitoring.resourcelifecycle.service.LifecycleJsonHelper;
import com.ondemandmonitoring.resourcelifecycle.service.MaintenanceLifecycleService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceAlertService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceAuditService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceEligibilityService;
import com.ondemandmonitoring.resourcelifecycle.service.ResourceUsageService;
import com.ondemandmonitoring.resourcelifecycle.service.WorkforceWorkloadService;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end service workflow tests running completely independent of PX4/Gazebo and Ollama.
 */
class ResourceLifecycleWorkflowTest {

    private final List<ResourceUsageRecordEntity> usageStore = new ArrayList<>();
    private final List<ResourceMaintenanceCycleEntity> cycleStore = new ArrayList<>();
    private final List<MaintenanceTicket> ticketStore = new ArrayList<>();
    private final List<ResourceHealthAlertEntity> alertStore = new ArrayList<>();
    private final List<ResourceLifecycleAuditEntity> auditStore = new ArrayList<>();

    private Device device;
    private ResourcePolicyEntity policy50h;

    private DroneHealthService droneHealthService;
    private MaintenanceLifecycleService maintenanceLifecycleService;
    private ResourceUsageService usageService;
    private ResourceEligibilityService eligibilityService;

    @BeforeEach
    void setUp() {
        DeviceModel model = new DeviceModel();
        model.setId("model-m300");
        model.setModelName("Matrice 300 RTK");
        model.setManufacturer("DJI");

        device = new Device();
        device.setId("drone-1");
        device.setName("Drone Alpha");
        device.setSerialNumber("SN-ALPHA-01");
        device.setDeviceModel(model);
        device.setStatus(DeviceStatus.AVAILABLE);

        policy50h = new ResourcePolicyEntity();
        policy50h.setId(UUID.randomUUID());
        policy50h.setFamily("MAINTENANCE");
        policy50h.setTargetId("model-m300");
        policy50h.setName("50h Preventive Policy");
        policy50h.setRevision(1L);
        policy50h.setActive(true);
        policy50h.setEffectiveAt(Instant.parse("2026-11-01T00:00:00Z"));
        policy50h.setRulesJson("{\"maxFlightHours\":50,\"monitorPercent\":60,\"warningPercent\":80}");
        policy50h.setReason("Standard 50h interval");
        policy50h.setCreatedBy("admin-1");
        policy50h.setUpdatedAt(Instant.now());

        DeviceRepository deviceRepo = mock(DeviceRepository.class);
        when(deviceRepo.findById("drone-1")).thenReturn(Optional.of(device));
        when(deviceRepo.existsById("drone-1")).thenReturn(true);
        when(deviceRepo.findAll()).thenAnswer(inv -> List.of(device));
        when(deviceRepo.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        ResourceUsageRecordRepository usageRepo = mock(ResourceUsageRecordRepository.class);
        when(usageRepo.save(any(ResourceUsageRecordEntity.class))).thenAnswer(inv -> {
            ResourceUsageRecordEntity item = inv.getArgument(0);
            usageStore.removeIf(u -> u.getId().equals(item.getId()));
            usageStore.add(item);
            return item;
        });
        when(usageRepo.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return usageStore.stream().filter(u -> u.getId().equals(id)).findFirst();
        });
        when(usageRepo.findByIdempotencyKey(any(String.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return usageStore.stream().filter(u -> key.equals(u.getIdempotencyKey())).findFirst();
        });
        when(usageRepo.findByResourceKindAndResourceIdAndStatusOrderByStartedAtAsc("DEVICE", "drone-1", "VERIFIED"))
                .thenAnswer(inv -> usageStore.stream()
                        .filter(u -> "VERIFIED".equals(u.getStatus()) && "drone-1".equals(u.getResourceId()))
                        .toList());

        ResourcePolicyRepository policyRepo = mock(ResourcePolicyRepository.class);
        when(policyRepo.findFirstByFamilyAndTargetIdAndActiveTrue("MAINTENANCE", "model-m300"))
                .thenAnswer(inv -> Optional.of(policy50h));
        when(policyRepo.findById(policy50h.getId())).thenReturn(Optional.of(policy50h));

        ResourceMaintenanceCycleRepository cycleRepo = mock(ResourceMaintenanceCycleRepository.class);
        when(cycleRepo.save(any(ResourceMaintenanceCycleEntity.class))).thenAnswer(inv -> {
            ResourceMaintenanceCycleEntity item = inv.getArgument(0);
            cycleStore.removeIf(c -> c.getId().equals(item.getId()));
            cycleStore.add(item);
            return item;
        });
        when(cycleRepo.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return cycleStore.stream().filter(c -> c.getId().equals(id)).findFirst();
        });
        when(cycleRepo.findByTicketId(any(String.class))).thenAnswer(inv -> {
            String tid = inv.getArgument(0);
            return cycleStore.stream().filter(c -> tid.equals(c.getTicketId())).findFirst();
        });
        when(cycleRepo.findByDeviceIdOrderByDetectedAtDesc("drone-1"))
                .thenAnswer(inv -> List.copyOf(cycleStore));
        when(cycleRepo.findByDeviceIdAndPolicyIdOrderByCycleDesc("drone-1", policy50h.getId()))
                .thenAnswer(inv -> List.copyOf(cycleStore));
        when(cycleRepo.existsByDeviceIdAndCheckpointAtIsNull("drone-1"))
                .thenAnswer(inv -> cycleStore.stream().anyMatch(c -> c.getCheckpointAt() == null));

        MaintenanceTicketRepository ticketRepo = mock(MaintenanceTicketRepository.class);
        when(ticketRepo.save(any(MaintenanceTicket.class))).thenAnswer(inv -> {
            MaintenanceTicket ticket = inv.getArgument(0);
            if (ticket.getId() == null) {
                ticket.setId("ticket-" + (ticketStore.size() + 1));
            }
            ticketStore.removeIf(t -> t.getId().equals(ticket.getId()));
            ticketStore.add(ticket);
            return ticket;
        });
        when(ticketRepo.findById(any(String.class))).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return ticketStore.stream().filter(t -> id.equals(t.getId())).findFirst();
        });
        when(ticketRepo.findAll()).thenAnswer(inv -> List.copyOf(ticketStore));

        ResourceHealthAlertRepository alertRepo = mock(ResourceHealthAlertRepository.class);
        when(alertRepo.findByDedupKey(any(String.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return alertStore.stream().filter(a -> key.equals(a.getDedupKey())).findFirst();
        });
        when(alertRepo.save(any(ResourceHealthAlertEntity.class))).thenAnswer(inv -> {
            ResourceHealthAlertEntity alert = inv.getArgument(0);
            alertStore.removeIf(a -> a.getId().equals(alert.getId()));
            alertStore.add(alert);
            return alert;
        });

        ResourceLifecycleAuditRepository auditRepo = mock(ResourceLifecycleAuditRepository.class);
        when(auditRepo.save(any(ResourceLifecycleAuditEntity.class))).thenAnswer(inv -> {
            ResourceLifecycleAuditEntity audit = inv.getArgument(0);
            auditStore.add(audit);
            return audit;
        });

        ResourceTimeLockRepository lockRepo = mock(ResourceTimeLockRepository.class);
        when(lockRepo.findAll()).thenReturn(List.of());

        AuthenticatedUserResolver userResolver = mock(AuthenticatedUserResolver.class);
        when(userResolver.getCurrentUserId()).thenReturn("manager-1");

        LifecycleJsonHelper jsonHelper = new LifecycleJsonHelper(new ObjectMapper());
        ResourceAuditService auditService = new ResourceAuditService(auditRepo, jsonHelper);
        ResourceAlertService alertService = new ResourceAlertService(alertRepo, auditService, userResolver);

        droneHealthService = new DroneHealthService(
                deviceRepo, usageRepo, policyRepo, cycleRepo, lockRepo, jsonHelper);
        maintenanceLifecycleService = new MaintenanceLifecycleService(
                deviceRepo, ticketRepo, policyRepo, cycleRepo,
                droneHealthService, alertService, auditService, jsonHelper, userResolver);
        usageService = new ResourceUsageService(
                usageRepo, deviceRepo, mock(UserRepository.class),
                maintenanceLifecycleService, auditService, userResolver);
        eligibilityService = new ResourceEligibilityService(
                deviceRepo, mock(UserRepository.class), droneHealthService, mock(WorkforceWorkloadService.class));
    }

    @Test
    void fullDemoFlowWithoutSimulator_48hTo51h_BlocksAssign_CheckpointPreservesLifetime() {
        Instant base = Instant.parse("2026-11-20T00:00:00Z");

        // Step 1: Record and verify 48h usage -> warning, 2h remaining
        var usage48 = usageService.recordUsage(new UsageRequest(
                "drone-1", "DEVICE", null, null, null,
                base, base.plusSeconds(48L * 3600L), 48L * 3600L, 12, 12000.0,
                "idem-48h", "Initial verified flight log", "logbook://2026-11-20", null));
        usageService.verifyUsage(usage48.id(), new VerificationRequest(true, "Verified against flight log"));

        DroneHealthResponse at48h = droneHealthService.getDroneHealth("drone-1");
        assertEquals("MAINTENANCE_DUE_SOON", at48h.maintenanceStatus());
        assertFalse(at48h.blocked());
        assertEquals(2L * 3600L, at48h.remainingSeconds());

        // Scenario 2: Remaining 2h, mission needing 4h is blocked
        EligibilityResponse fourHourCheck = eligibilityService.evaluateDeviceEligibility(
                "drone-1", base.plusSeconds(50L * 3600L), base.plusSeconds(54L * 3600L));
        assertFalse(fourHourCheck.eligible());
        assertTrue(fourHourCheck.blockingReasons().contains("PROJECTED_MISSION_EXCEEDS_REMAINING_MAINTENANCE_BUDGET"));

        // Step 2: Record and verify +3h -> total 51h -> breaches 50h policy -> creates exactly 1 ticket
        var usage3 = usageService.recordUsage(new UsageRequest(
                "drone-1", "DEVICE", null, null, null,
                base.plusSeconds(50L * 3600L), base.plusSeconds(53L * 3600L), 3L * 3600L, 2, 3000.0,
                "idem-3h", "Survey sortie", "logbook://2026-11-22", null));
        usageService.verifyUsage(usage3.id(), new VerificationRequest(true, "Confirmed"));

        assertEquals(1, ticketStore.size());
        assertEquals(1, cycleStore.size());
        assertEquals(DeviceStatus.MAINTENANCE, device.getStatus());

        // Re-evaluating again must NOT create a duplicate ticket
        maintenanceLifecycleService.evaluateAndTriggerIfBreached("drone-1");
        assertEquals(1, ticketStore.size());

        // Assignment is blocked
        assertThrows(ApiException.class, () -> eligibilityService.requireResourceEligibleForMission(
                "drone-1", false, base.plusSeconds(60L * 3600L), base.plusSeconds(61L * 3600L), "mission-new"));

        // Step 3: Maintainer submits work -> still blocked until post-maintenance inspection passes
        UUID cycleId = cycleStore.getFirst().getId();
        maintenanceLifecycleService.submitWork(cycleId, new MaintenanceWorkSubmissionRequest(
                "Inspected motors, replaced propellers, calibrated IMU",
                "maint://evidence-001",
                List.of("Motor check", "Propeller balance"),
                List.of("CW/CCW Propellers")));
        assertEquals(DeviceStatus.MAINTENANCE, device.getStatus());
        assertTrue(maintenanceLifecycleService.requiresPostMaintenanceInspection(ticketStore.getFirst().getId()));

        // Step 4: Post-maintenance inspection PASSED -> checkpoint created, lifetime stays 51h, sinceMaintenance resets to 0, unlocked
        MaintenanceCycleResponse inspected = maintenanceLifecycleService.recordInspection(
                cycleId, new MaintenanceInspectionRequest(true, "All post-maintenance checks passed"));
        assertNotNull(inspected.checkpointAt());
        assertEquals(51L * 3600L, inspected.checkpointSeconds());
        assertEquals(DeviceStatus.AVAILABLE, device.getStatus());

        DroneHealthResponse afterCheckpoint = droneHealthService.getDroneHealth("drone-1");
        assertEquals(51L * 3600L, afterCheckpoint.lifetimeActualSeconds());
        assertEquals(0L, afterCheckpoint.sinceMaintenanceSeconds());
        assertEquals("HEALTHY", afterCheckpoint.maintenanceStatus());
        assertFalse(afterCheckpoint.blocked());
    }
}
