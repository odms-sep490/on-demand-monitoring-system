package com.ondemandmonitoring.device.maintenance.service;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceMaintenanceCycleEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceMaintenanceCycleRepository;
import com.ondemandmonitoring.resourcelifecycle.service.DroneHealthService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates device maintenance blocking, conditional availability restoration,
 * and lifecycle checkpoint creation without ever resetting lifetime counters or
 * interrupting an airborne drone.
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class MaintenanceEligibilityGuard {

    private final DeviceRepository deviceRepository;
    private final MaintenanceTicketRepository ticketRepository;

    @Autowired(required = false)
    private ResourceMaintenanceCycleRepository cycleRepository;

    @Autowired(required = false)
    private DroneHealthService droneHealthService;

    public record UnlockOutcome(
            boolean checkpointCreated,
            boolean deviceUnlocked,
            List<String> remainingBlockers) {
    }

    /**
     * Marks the device as MAINTENANCE if it is not currently airborne/in an active flight.
     * Never sends commands to stop an airborne drone.
     */
    @Transactional
    public void blockDeviceForMaintenance(Device device) {
        if (device == null) {
            return;
        }
        DeviceStatus current = device.getStatus();
        if (current == DeviceStatus.IN_MISSION
                || current == DeviceStatus.ACTIVE_MISSION
                || current == DeviceStatus.RETURNING) {
            return;
        }
        if (current != DeviceStatus.MAINTENANCE) {
            device.setStatus(DeviceStatus.MAINTENANCE);
            deviceRepository.save(device);
        }
    }

    @Transactional(readOnly = true)
    public boolean hasBlockingMaintenanceTicket(String deviceId, String excludeTicketId) {
        return ticketRepository.existsBlockingTicketForDevice(deviceId, excludeTicketId);
    }

    /**
     * Called strictly upon Manager return acceptance.
     * Creates a lifecycle checkpoint (preserving all lifetime counters) when a cycle exists,
     * and restores DeviceStatus.AVAILABLE only when zero remaining blockers exist.
     */
    @Transactional
    public UnlockOutcome completeAndEvaluateUnlock(
            MaintenanceTicket ticket,
            String managerId,
            Instant decidedAt,
            String reportNotes) {
        Device device = ticket.getDevice();
        if (device == null) {
            return new UnlockOutcome(false, false, List.of("DEVICE_NOT_LINKED"));
        }

        boolean checkpointCreated = false;
        if (cycleRepository != null && droneHealthService != null) {
            Optional<ResourceMaintenanceCycleEntity> cycleOpt = cycleRepository.findByTicketId(ticket.getId());
            if (cycleOpt.isPresent()) {
                ResourceMaintenanceCycleEntity cycle = cycleOpt.get();
                if (cycle.getCheckpointAt() == null) {
                    DroneHealthResponse health = droneHealthService.evaluateDevice(device, null);
                    long lifetimeSeconds = health.lifetimeActualSeconds() == null
                            ? 0L
                            : health.lifetimeActualSeconds();
                    long lifetimeCycles = health.lifetimeCycles();

                    cycle.setWorkNotes(reportNotes);
                    cycle.setInspectionPassed(Boolean.TRUE);
                    cycle.setInspectedBy(managerId);
                    cycle.setInspectedAt(decidedAt);
                    cycle.setCheckpointAt(decidedAt);
                    cycle.setCheckpointSeconds(lifetimeSeconds);
                    cycle.setCheckpointCycles(lifetimeCycles);
                    cycleRepository.save(cycle);
                    checkpointCreated = true;
                }
            }
        }

        List<String> blockers = new ArrayList<>();
        if (ticketRepository.existsBlockingTicketForDevice(device.getId(), ticket.getId())) {
            blockers.add("OTHER_OPEN_MAINTENANCE_TICKET");
        }
        if (cycleRepository != null && cycleRepository.existsByDeviceIdAndCheckpointAtIsNull(device.getId())) {
            blockers.add("OTHER_OPEN_MAINTENANCE_CYCLE");
        }
        if (device.getStatus() != DeviceStatus.MAINTENANCE && device.getStatus() != DeviceStatus.AVAILABLE) {
            blockers.add("DEVICE_STATUS_" + device.getStatus().name());
        }

        boolean unlocked = false;
        if (blockers.isEmpty() && device.getStatus() == DeviceStatus.MAINTENANCE) {
            device.setStatus(DeviceStatus.AVAILABLE);
            deviceRepository.save(device);
            unlocked = true;
        }
        return new UnlockOutcome(checkpointCreated, unlocked, List.copyOf(blockers));
    }
}
