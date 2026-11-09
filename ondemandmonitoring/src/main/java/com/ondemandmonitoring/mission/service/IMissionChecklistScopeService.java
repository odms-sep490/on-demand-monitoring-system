package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.Mission;

public interface IMissionChecklistScopeService {
    boolean isMaterialized(Mission mission);
    void requireMaterialized(Mission mission);
}
