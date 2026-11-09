package com.ondemandmonitoring.device.service.impl;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import com.ondemandmonitoring.device.dto.request.TelemetryRequest;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.device.service.IDeviceTelemetryService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.service.IDeviceConnectionService;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DeviceTelemetryServiceImpl implements IDeviceTelemetryService {

    IDeviceConnectionService deviceConnectionService;
    DeviceTelemetryRepository telemetryRepository;
    DeviceRepository deviceRepository;

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<DeviceTelemetry> findLatest(String missionId, String deviceId) {
        return telemetryRepository
                .findLatestForMissionDevice(missionId, deviceId, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public void record(String deviceId, TelemetryRequest request) {
        DeviceConnection connection = deviceConnectionService.requireActiveTelemetryConnection(
                deviceId,
                request.getMissionId());
        Device device = connection.getDevice();

        if ((request.getLatitude() == null) != (request.getLongitude() == null)
                || (request.getLatitude() != null
                    && (!Double.isFinite(request.getLatitude()) || !Double.isFinite(request.getLongitude())
                        || Math.abs(request.getLatitude()) > 90 || Math.abs(request.getLongitude()) > 180))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Telemetry GPS must contain valid WGS84 latitude and longitude");
        }

        DeviceTelemetry telemetry = new DeviceTelemetry();
        telemetry.setDeviceConnection(connection);
        telemetry.setBatteryPercent(request.getBatteryPercent());
        telemetry.setSimX(request.getSimX());
        telemetry.setSimY(request.getSimY());
        telemetry.setLatitude(request.getLatitude());
        telemetry.setLongitude(request.getLongitude());
        telemetry.setAbsoluteAltitudeM(request.getAbsoluteAltitude());
        telemetry.setRelativeAltitudeM(request.getRelativeAltitude());
        telemetry.setConnected(request.getConnected());
        telemetry.setRecordedAt(Instant.now());
        DeviceTelemetry saved = telemetryRepository.save(telemetry);

        device.setLastSeenAt(LocalDateTime.now());
        deviceRepository.save(device);

    }
}
