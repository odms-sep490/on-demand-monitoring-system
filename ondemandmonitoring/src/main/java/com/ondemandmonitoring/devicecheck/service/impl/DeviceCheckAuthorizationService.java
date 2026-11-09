package com.ondemandmonitoring.devicecheck.service.impl;

import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.IDeviceCheckAuthorizationService;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("deviceCheckAuthorizationService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceCheckAuthorizationService implements IDeviceCheckAuthorizationService {
    private final PersistedPreDeviceCheckRepository preChecks;
    private final PersistedPostDeviceCheckRepository postChecks;
    private final IMissionAuthorizationService missionAuthorization;

    @Override
    public boolean canViewPreCheck(String id) {
        return preChecks.findById(id).map(run -> missionAuthorization.canViewMission(run.getMission().getId()))
                .orElse(false);
    }

    @Override
    public boolean canInspectPreCheck(String id) {
        return preChecks.findById(id).map(run -> missionAuthorization.canOperatePayload(run.getMission().getId()))
                .orElse(false);
    }

    @Override
    public boolean canViewPostCheck(String id) {
        return postChecks.findById(id).map(run -> missionAuthorization.canViewMission(run.getMission().getId()))
                .orElse(false);
    }

    @Override
    public boolean canInspectPostCheck(String id) {
        return postChecks.findById(id).map(run -> missionAuthorization.canMaintainDevice(run.getMission().getId()))
                .orElse(false);
    }
}
