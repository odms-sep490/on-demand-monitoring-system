package com.ondemandmonitoring.device.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import com.ondemandmonitoring.device.mapper.DeviceMapper;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.service.IDeviceAvailabilityService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Advisory read only. Assignment writers still acquire locks and revalidate reservations. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceAvailabilityServiceImpl implements IDeviceAvailabilityService {
    private final MissionRepository missions;
    private final DeviceRepository devices;
    private final DeviceMapper mapper;

    @Override
    public List<DeviceResponse> getAvailableForMission(String missionId) {
        var mission = missions.findById(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        var start = mission.getScheduledStartAt();
        var end = mission.getScheduledEndAt();
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Mission schedule is required for availability");
        }
        return devices.findAvailableForMission(missionId, start, end).stream()
                .map(mapper::toResponse).toList();
    }
}
