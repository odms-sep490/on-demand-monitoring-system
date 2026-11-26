package com.ondemandmonitoring.device.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceEligibilityGuard;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaintenanceEligibilityGuardTest {

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private MaintenanceTicketRepository ticketRepository;

    private MaintenanceEligibilityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new MaintenanceEligibilityGuard(deviceRepository, ticketRepository);
    }

    @Test
    void doesNotUnlockDeviceWhenAnotherBlockingTicketExists() {
        Device drone = new Device();
        drone.setId("dev-1");
        drone.setStatus(DeviceStatus.MAINTENANCE);

        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setId("tkt-1");
        ticket.setDevice(drone);

        when(ticketRepository.existsBlockingTicketForDevice("dev-1", "tkt-1")).thenReturn(true);

        MaintenanceEligibilityGuard.UnlockOutcome outcome =
                guard.completeAndEvaluateUnlock(ticket, "mgr-1", Instant.now(), "Work complete");

        assertThat(outcome.deviceUnlocked()).isFalse();
        assertThat(outcome.remainingBlockers()).contains("OTHER_OPEN_MAINTENANCE_TICKET");
        assertThat(drone.getStatus()).isEqualTo(DeviceStatus.MAINTENANCE);
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void unlocksDeviceWhenNoOtherBlockersRemain() {
        Device drone = new Device();
        drone.setId("dev-1");
        drone.setStatus(DeviceStatus.MAINTENANCE);

        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setId("tkt-1");
        ticket.setDevice(drone);

        when(ticketRepository.existsBlockingTicketForDevice("dev-1", "tkt-1")).thenReturn(false);

        MaintenanceEligibilityGuard.UnlockOutcome outcome =
                guard.completeAndEvaluateUnlock(ticket, "mgr-1", Instant.now(), "Work complete");

        assertThat(outcome.deviceUnlocked()).isTrue();
        assertThat(outcome.remainingBlockers()).isEmpty();
        assertThat(drone.getStatus()).isEqualTo(DeviceStatus.AVAILABLE);
        verify(deviceRepository).save(drone);
    }

    @Test
    void doesNotInterruptAirborneDroneWhenBlockingForMaintenance() {
        Device flyingDrone = new Device();
        flyingDrone.setId("dev-2");
        flyingDrone.setStatus(DeviceStatus.IN_MISSION);

        guard.blockDeviceForMaintenance(flyingDrone);

        assertThat(flyingDrone.getStatus()).isEqualTo(DeviceStatus.IN_MISSION);
        verify(deviceRepository, never()).save(any());
    }
}
