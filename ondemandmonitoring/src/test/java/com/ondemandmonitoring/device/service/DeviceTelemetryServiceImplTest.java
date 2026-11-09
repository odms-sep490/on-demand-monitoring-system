package com.ondemandmonitoring.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import com.ondemandmonitoring.device.dto.request.TelemetryRequest;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.device.service.impl.DeviceTelemetryServiceImpl;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.service.IDeviceConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceTelemetryServiceImplTest {

    @Mock IDeviceConnectionService connections;
    @Mock DeviceTelemetryRepository telemetryRepository;
    @Mock DeviceRepository deviceRepository;

    DeviceTelemetryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeviceTelemetryServiceImpl(
                connections,
                telemetryRepository,
                deviceRepository);
    }

    @Test
    void recordsTelemetryAgainstActiveBoundDeviceConnection() {
        Device device = new Device();
        device.setId("device-1");
        device.setDeviceCode("DRN-0050");
        MissionDeviceAssignment assignment = new MissionDeviceAssignment();
        assignment.setDevice(device);
        DeviceConnection connection = new DeviceConnection();
        connection.setId("connection-1");
        connection.setDeviceAssignment(assignment);

        TelemetryRequest request = new TelemetryRequest();
        request.setBatteryPercent(94.5);
        request.setSimX(10.0);
        request.setSimY(20.0);
        request.setRelativeAltitude(12.0);
        request.setConnected(true);
        request.setMissionId("mission-1");

        when(connections.requireActiveTelemetryConnection("device-1", "mission-1")).thenReturn(connection);
        when(telemetryRepository.save(any(DeviceTelemetry.class))).thenAnswer(invocation -> {
            DeviceTelemetry telemetry = invocation.getArgument(0);
            telemetry.setId("telemetry-1");
            return telemetry;
        });

        service.record("device-1", request);

        ArgumentCaptor<DeviceTelemetry> telemetry = ArgumentCaptor.forClass(DeviceTelemetry.class);
        verify(telemetryRepository).save(telemetry.capture());
        assertThat(telemetry.getValue().getDeviceConnection()).isSameAs(connection);
        assertThat(telemetry.getValue().getBatteryPercent()).isEqualTo(94.5);
        assertThat(telemetry.getValue().getConnected()).isTrue();
        verify(deviceRepository).save(device);
    }
}
