package com.ondemandmonitoring.devicecheck.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheckItem;
import com.ondemandmonitoring.devicecheck.dto.request.PreDeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.impl.PersistedPreDeviceCheckService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersistedPreDeviceCheckServiceTest {

    @Mock PersistedPreDeviceCheckRepository runRepository;
    @Mock MissionRepository missionRepository;
    @Mock com.ondemandmonitoring.device.repository.DeviceTelemetryRepository telemetryRepository;
    @Mock com.ondemandmonitoring.user.service.AuthenticatedUserResolver authenticatedUserResolver;
    @Mock com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository weatherCheckRepository;
    @InjectMocks PersistedPreDeviceCheckService service;

    @Test
    void reusesRecentlyStartedCheckingRun() {
        Mission mission = mission("mission-1");
        PersistedPreDeviceCheck activeRun = checkingRun("run-1", mission, Instant.now());
        when(missionRepository.findByIdForUpdate("mission-1")).thenReturn(Optional.of(mission));
        when(runRepository.findFirstByMissionIdOrderByCreatedAtDesc("mission-1"))
                .thenReturn(Optional.of(activeRun));

        var response = service.start("mission-1");

        assertThat(response.getId()).isEqualTo("run-1");
        verify(runRepository, never()).save(any());
    }

    @Test
    void createsNewRunWhenPreviousCheckingRunIsStale() {
        Mission mission = mission("mission-1");
        PersistedPreDeviceCheck staleRun = checkingRun(
                "run-old", mission, Instant.now().minusSeconds(31));
        when(missionRepository.findByIdForUpdate("mission-1")).thenReturn(Optional.of(mission));
        when(runRepository.findFirstByMissionIdOrderByCreatedAtDesc("mission-1"))
                .thenReturn(Optional.of(staleRun));
        when(runRepository.save(any(PersistedPreDeviceCheck.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.start("mission-1");

        assertThat(response.getStatus()).isEqualTo(PreDeviceCheckStatus.FAILED);
        assertThat(response.getItems()).hasSize(response.getTotalChecks());
        verify(runRepository).save(any(PersistedPreDeviceCheck.class));
    }

    @Test
    void doesNotRegressTerminalItemToChecking() {
        Mission mission = mission("mission-1");
        PersistedPreDeviceCheck run = checkingRun("run-1", mission, Instant.now());
        PersistedPreDeviceCheckItem media = new PersistedPreDeviceCheckItem();
        media.setPreDeviceCheck(run);
        media.setCheckType("MEDIA");
        media.setCheckName("Media Upload");
        media.setStatus(PreDeviceItemStatus.PASSED);
        media.setCheckedAt(Instant.now());
        media.setExpiresAt(Instant.now().plusSeconds(300));
        run.getItems().add(media);
        run.setPassedChecks(1);
        run.setTotalChecks(1);
        when(runRepository.findByIdForUpdate("run-1")).thenReturn(Optional.of(run));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(missionRepository.findByIdForUpdate("mission-1")).thenReturn(Optional.of(mission));
        PreDeviceCheckItemUpdateRequest update = new PreDeviceCheckItemUpdateRequest();
        update.setStatus(PreDeviceItemStatus.CHECKING);
        update.setMessage("stale in-flight response");

        var response = service.update("run-1", "MEDIA", update);

        assertThat(response.getItems().getFirst().getStatus())
                .isEqualTo(PreDeviceItemStatus.PASSED);
        assertThat(response.getItems().getFirst().getMessage()).isNull();
    }

    @Test
    void readsPreflightForReleasedDeviceWithoutMixingOtherDeviceResults() {
        Mission mission = mission("mission-1");
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.COMPLETED);
        var device = new com.ondemandmonitoring.device.domain.Device();
        device.setId("device-1");
        var assignment = new com.ondemandmonitoring.mission.domain.MissionDeviceAssignment();
        assignment.setId("assignment-1");
        assignment.setDevice(device);
        assignment.setIsCurrent(false);
        assignment.setReleasedAt(Instant.now());
        var run = checkingRun("run-1", mission, Instant.now());
        run.setMissionDeviceAssignment(assignment);
        var otherDevice = new com.ondemandmonitoring.device.domain.Device();
        otherDevice.setId("device-2");
        var otherAssignment = new com.ondemandmonitoring.mission.domain.MissionDeviceAssignment();
        otherAssignment.setDevice(otherDevice);
        var otherRun = checkingRun("run-2", mission, Instant.now());
        otherRun.setMissionDeviceAssignment(otherAssignment);
        when(missionRepository.existsById("mission-1")).thenReturn(true);
        when(runRepository.findByMissionIdOrderByCreatedAtDesc("mission-1"))
                .thenReturn(java.util.List.of(otherRun, run));
        var response = service.current("mission-1", "device-1");
        assertThat(response.getId()).isEqualTo("run-1");
        assertThat(response.getDeviceId()).isEqualTo("device-1");
        assertThat(service.current("mission-1", "device-without-check")).isNull();
    }

    private static Mission mission(String id) {
        Mission mission = new Mission();
        mission.setId(id);
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.PENDING_CONFIRMATION);
        return mission;
    }

    private static PersistedPreDeviceCheck checkingRun(
            String id,
            Mission mission,
            Instant startedAt) {
        PersistedPreDeviceCheck run = new PersistedPreDeviceCheck();
        run.setId(id);
        run.setMission(mission);
        run.setStatus(PreDeviceCheckStatus.CHECKING);
        run.setTotalChecks(13);
        run.setPassedChecks(0);
        run.setFailedChecks(0);
        run.setStartedAt(startedAt);
        return run;
    }
}
