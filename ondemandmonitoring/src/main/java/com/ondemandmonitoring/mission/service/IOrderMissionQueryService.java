package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import java.util.List;

public interface IOrderMissionQueryService {
    List<MissionResponse> getMissionsByOrderId(String orderId);
}
