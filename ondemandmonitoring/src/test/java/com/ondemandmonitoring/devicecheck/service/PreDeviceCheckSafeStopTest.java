package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.devicecheck.service.impl.PreDeviceCheckCompletionServiceImpl;
import com.ondemandmonitoring.devicecheck.service.impl.PersistedPreDeviceCheckService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionSafeStopStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.impl.MissionRuntimeGate;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreDeviceCheckSafeStopTest {
    @ParameterizedTest
    @EnumSource(value=MissionStatus.class, names={"CONNECTED","PREFLIGHT_CHECKING","READY_TO_FLY"})
    void normalPassedPreflightStillCreatesReadyMission(MissionStatus status) {
        var mission=new Mission(); mission.setId("m"); mission.setStatus(status);
        var missions=mock(MissionRepository.class);
        when(missions.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        var devices=mock(com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository.class);
        var device=new com.ondemandmonitoring.device.domain.Device(); device.setId("d");
        var assigned=com.ondemandmonitoring.mission.domain.MissionDeviceAssignment.builder().device(device).build();
        when(devices.findFirstByMissionIdOrderByCreatedAtDesc("m")).thenReturn(Optional.of(assigned));
        var crew=mock(com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository.class);
        var user=new com.ondemandmonitoring.user.domain.User(); user.setId("pilot");
        when(crew.findAllByMissionIdAndAssignedRoleAndIsCurrentTrue("m",com.ondemandmonitoring.mission.enums.MissionStaffRole.PILOT))
                .thenReturn(java.util.List.of(com.ondemandmonitoring.mission.domain.MissionStaffAssignment.builder().staff(user)
                        .responseStatus(com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus.ACCEPTED).build()));
        var runs=mock(com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository.class);
        var run=new com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck();
        run.setStatus(com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckStatus.PASSED);
        when(runs.findFirstByMissionIdOrderByCreatedAtDesc("m")).thenReturn(Optional.of(run));
        var tokens=mock(com.ondemandmonitoring.mission.service.IFlightTokenService.class);
        var weather=mock(com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository.class);
        var mapper=mock(com.ondemandmonitoring.devicecheck.mapper.PreDeviceCheckCompletionMapper.class);
        new PreDeviceCheckCompletionServiceImpl(missions,devices,crew,runs,weather,tokens,mapper).complete("m","d");
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.READY_TO_FLY);
        verify(tokens).issueFlightToken("m","d","pilot"); verify(missions).save(mission);
    }
    @ParameterizedTest
    @EnumSource(MissionSafeStopStatus.class)
    void readyWriterAndPreflightStartFailClosedForEveryStopPhase(MissionSafeStopStatus phase) {
        var mission = new Mission(); mission.setId("m"); mission.setStatus(MissionStatus.READY_TO_FLY);
        mission.setSafeStopStatus(phase);
        var repository = mock(MissionRepository.class);
        when(repository.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        var writer = new PreDeviceCheckCompletionServiceImpl(repository, null, null, null, null, null, null);
        assertThatThrownBy(() -> writer.complete("m", "device")).isInstanceOf(ApiException.class);
        var start = new PersistedPreDeviceCheckService(null, repository, null, null, null, null);
        assertThatThrownBy(() -> start.start("m")).isInstanceOf(ApiException.class);
        verify(repository, never()).save(any());
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.READY_TO_FLY); // persisted lifecycle is not a reset
    }

    @ParameterizedTest
    @EnumSource(value = MissionStatus.class, names = {"IN_FLIGHT", "RETURNING", "PENDING_REVIEW", "COMPLETED", "CANCELLED", "FAILED"})
    void stalePassedPreflightCannotResetLaterLifecycle(MissionStatus status) {
        var mission = new Mission(); mission.setId("m"); mission.setStatus(status);
        var repository = mock(MissionRepository.class);
        when(repository.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        var writer = new PreDeviceCheckCompletionServiceImpl(repository, null, null, null, null, null, null);
        assertThatThrownBy(() -> writer.complete("m", "device")).isInstanceOf(ApiException.class);
        assertThat(mission.getStatus()).isEqualTo(status);
        verify(repository, never()).save(any());
    }
}
