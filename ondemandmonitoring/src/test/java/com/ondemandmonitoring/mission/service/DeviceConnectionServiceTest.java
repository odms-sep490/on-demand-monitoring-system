package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.DeviceConnectionRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.impl.DeviceConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceConnectionServiceTest {

    @Mock
    private MissionRepository missionRepository;
    @Mock
    private DeviceConnectionRepository deviceConnectionRepository;
    @Mock
    private MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    @Mock
    private MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private MissionMapper missionMapper;

    private DeviceConnectionService deviceConnectionService;

    @BeforeEach
    void setUp() {
        deviceConnectionService = new DeviceConnectionService(
                missionRepository,
                deviceConnectionRepository,
                missionDeviceAssignmentRepository,
                missionStaffAssignmentRepository,
                deviceRepository,
                missionMapper
        , com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope(),
                mock(com.ondemandmonitoring.mission.service.IResourceReservationService.class));
        lenient().when(missionRepository.findByIdForUpdate(anyString())).thenAnswer(call -> missionRepository.findById(call.getArgument(0)));

        lenient().when(missionMapper.toResponse(any())).thenAnswer(inv -> {
            Mission m = inv.getArgument(0);
            if (m == null) return null;
            return MissionResponse.builder()
                    .id(m.getId())
                    .missionCode(m.getMissionCode())
                    .status(m.getStatus())
                    .build();
        });
    }

    @Test
    @DisplayName("connectGcs creates gcs_sessions / device_connections record with CONNECTED status")
    void connectGcs_Success() {
        String missionId = "M-1";
        Mission mission = new Mission();
        mission.setId(missionId);
        mission.setStatus(MissionStatus.SCHEDULED);

        Device device = new Device();
        device.setId("D-1");
        device.setDeviceCode("DRONE-01");

        MissionDeviceAssignment mda = new MissionDeviceAssignment();
        mda.setMission(mission);
        mda.setDevice(device);
        mda.setIsCurrent(true);

        MissionStaffAssignment msa = new MissionStaffAssignment();
        msa.setMission(mission);
        msa.setIsCurrent(true);
        msa.setAssignedRole(MissionStaffRole.OPERATOR);
        msa.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);

        when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
        when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId))
                .thenReturn(List.of(mda));
        when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(missionId))
                .thenReturn(List.of(msa));
        when(deviceConnectionRepository.save(any(DeviceConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

        MissionResponse response = deviceConnectionService.connectGcs(missionId);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(MissionStatus.CONNECTED);

        ArgumentCaptor<DeviceConnection> sessionCaptor = ArgumentCaptor.forClass(DeviceConnection.class);
        verify(deviceConnectionRepository).save(sessionCaptor.capture());
        DeviceConnection savedSession = sessionCaptor.getValue();
        assertThat(savedSession.getConnectionStatus()).isEqualTo("CONNECTED");
        assertThat(savedSession.getTelemetryActive()).isTrue();
    }

    @Test
    @DisplayName("disconnectGcs sets session status to DISCONNECTED")
    void disconnectGcs_Success() {
        String missionId = "M-1";
        Mission mission = new Mission();
        mission.setId(missionId);
        mission.setStatus(MissionStatus.CONNECTED);

        DeviceConnection activeSession = new DeviceConnection();
        activeSession.setMission(mission);
        activeSession.setConnectionStatus("CONNECTED");
        activeSession.setTelemetryActive(true);

        when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
        when(deviceConnectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(missionId, "CONNECTED"))
                .thenReturn(Optional.of(activeSession));

        deviceConnectionService.disconnectGcs(missionId, "MISSION_COMPLETED");

        assertThat(activeSession.getConnectionStatus()).isEqualTo("DISCONNECTED");
        assertThat(activeSession.getTelemetryActive()).isFalse();
        assertThat(activeSession.getDisconnectReason()).isEqualTo("MISSION_COMPLETED");
        verify(deviceConnectionRepository).save(activeSession);
    }

    @Test
    @DisplayName("handleGcsSessionLost updates session to LOST and triggers Return-To-Launch (RETURNING)")
    void handleGcsSessionLost_TriggersRTL() {
        String missionId = "M-1";
        Mission mission = new Mission();
        mission.setId(missionId);
        mission.setStatus(MissionStatus.IN_PROGRESS);

        Device device = new Device();
        device.setId("D-1");
        device.setStatus(DeviceStatus.ACTIVE_MISSION);

        MissionDeviceAssignment mda = new MissionDeviceAssignment();
        mda.setMission(mission);
        mda.setDevice(device);
        mda.setIsCurrent(true);

        DeviceConnection activeSession = new DeviceConnection();
        activeSession.setMission(mission);
        activeSession.setConnectionStatus("CONNECTED");

        when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
        when(deviceConnectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(missionId, "CONNECTED"))
                .thenReturn(Optional.of(activeSession));
        when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId))
                .thenReturn(List.of(mda));
        when(missionRepository.save(any(Mission.class))).thenAnswer(inv -> inv.getArgument(0));

        MissionResponse response = deviceConnectionService.handleGcsSessionLost(missionId, "SIGNAL_LOSS_COMM_TIMEOUT");

        assertThat(response.getStatus()).isEqualTo(MissionStatus.RETURNING);
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.RETURNING);
        assertThat(activeSession.getConnectionStatus()).isEqualTo("LOST");
        assertThat(activeSession.getDisconnectReason()).isEqualTo("SIGNAL_LOSS_COMM_TIMEOUT");

        verify(deviceConnectionRepository).save(activeSession);
        verify(deviceRepository).save(device);
    }
}
