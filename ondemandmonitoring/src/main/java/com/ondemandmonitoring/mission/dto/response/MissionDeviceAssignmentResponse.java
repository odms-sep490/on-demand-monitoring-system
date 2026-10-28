package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionDeviceAssignmentResponse {
    String id;
    String deviceId;
    String deviceCode;
    String deviceName;
    String deviceSerialNumber;
    String deviceStatus;
    DeviceRole deviceRole;
    MissionDeviceAssignmentStatus checkupStatus;
    Instant assignedAt;
}
