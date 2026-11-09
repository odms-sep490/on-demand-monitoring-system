package com.ondemandmonitoring.device.service;

import com.ondemandmonitoring.device.dto.request.TelemetryRequest;

public interface IDeviceTelemetryService {

    void record(String deviceId, TelemetryRequest request);

    /** Latest persisted telemetry of the given drone within the mission, regardless of GPS presence. */
    java.util.Optional<com.ondemandmonitoring.device.domain.DeviceTelemetry> findLatest(String missionId, String deviceId);
}
