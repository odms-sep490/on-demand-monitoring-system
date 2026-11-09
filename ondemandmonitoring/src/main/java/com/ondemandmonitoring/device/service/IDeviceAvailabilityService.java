package com.ondemandmonitoring.device.service;

import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import java.util.List;

public interface IDeviceAvailabilityService {
    List<DeviceResponse> getAvailableForMission(String missionId);
}
