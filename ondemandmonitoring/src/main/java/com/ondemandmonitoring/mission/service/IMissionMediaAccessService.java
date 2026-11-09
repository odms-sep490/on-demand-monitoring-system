package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.response.MissionMediaContext;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import java.util.List;

/** Public mission boundary for media use cases. Authorization stays with mission ownership. */
public interface IMissionMediaAccessService {
    MissionMediaContext authorizeOperator(String missionIdentifier);
    void requireUploadPermission(String missionIdentifier);
    void requireAssignedDevice(String missionId, String deviceId);
    MissionDeviceAssignment requireDeviceAssignment(String missionId, String deviceId);
    String authorizeCustomer(String missionIdentifier);
    List<String> ownCustomerMissionIds();
}
