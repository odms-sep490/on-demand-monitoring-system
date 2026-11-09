package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.Mission;

public interface IMissionChecklistMaterializationService {
    void materialize(Mission mission);
}
