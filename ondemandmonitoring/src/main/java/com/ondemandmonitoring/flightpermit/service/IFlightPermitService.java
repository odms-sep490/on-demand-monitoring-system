package com.ondemandmonitoring.flightpermit.service;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;

public interface IFlightPermitService {
    MissionResponse updateFlightPermit(String missionId, FlightPermitUpdateRequest request);

    void requireApprovedForExecution(Mission mission);
}
