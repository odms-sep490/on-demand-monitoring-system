package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.response.MissionReworkContextResponse;

public interface IMissionReworkContextService {
    MissionReworkContextResponse get(String missionId);
}
