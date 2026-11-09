package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPostDeviceCheckResponse;
import com.ondemandmonitoring.mission.dto.request.PostFlightStatusRequest;
import com.ondemandmonitoring.mission.enums.InspectionResult;
import java.util.List;
import java.util.Map;

public interface IPersistedPostDeviceCheckService {
    PersistedPostDeviceCheckResponse start(String missionId);
    List<PersistedPostDeviceCheckResponse> history(String missionId);
    PersistedPostDeviceCheckResponse current(String missionId);
    PersistedPostDeviceCheckResponse get(String id);
    PersistedPostDeviceCheckResponse update(String id, String type, DeviceCheckItemUpdateRequest request);
    void recordInspection(String missionId, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot);
}

