package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.request.MissionCancelRequest;
import com.ondemandmonitoring.mission.dto.response.MissionCancellationResponse;

public interface IMissionCancellationService {
    MissionCancellationResponse cancel(String missionId, MissionCancelRequest request);
}
