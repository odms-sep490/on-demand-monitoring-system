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
    @Mock com.ondemandmonitoring.mission.repository.DeviceConnectionRepository connectionRepository;
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

    private static Mission mission(String id) {
        Mission mission = new Mission();
        mission.setId(id);
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.CONNECTED);
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
