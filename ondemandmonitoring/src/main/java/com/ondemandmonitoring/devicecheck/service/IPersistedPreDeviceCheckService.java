package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.devicecheck.dto.request.PreDeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import java.util.List;

public interface IPersistedPreDeviceCheckService {
    PersistedPreDeviceCheckResponse start(String missionId);
    List<PersistedPreDeviceCheckResponse> history(String missionId);
    PersistedPreDeviceCheckResponse current(String missionId);
    void refreshFlightPermitCheck(String missionId);
    PersistedPreDeviceCheckResponse get(String id);
    PersistedPreDeviceCheckResponse update(String id, String type, PreDeviceCheckItemUpdateRequest request);
    PersistedPreDeviceCheckResponse recordSystemResult(
            String id, String type, PreDeviceItemStatus status, String message);
    PersistedPreDeviceCheckResponse recheck(String id, String type);
}

