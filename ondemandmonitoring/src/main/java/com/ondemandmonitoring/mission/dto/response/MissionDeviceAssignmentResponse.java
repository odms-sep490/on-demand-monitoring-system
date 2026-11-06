package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionDeviceCheckStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Builder(toBuilder = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionDeviceAssignmentResponse {
    String id;
    String deviceId;
    String deviceCode;
    String deviceName;
    String deviceSerialNumber;
    String deviceStatus;
    DeviceRole deviceRole;
    MissionDeviceAssignmentStatus status;
    MissionDeviceCheckStatus checkupStatus;
    Instant assignedAt;
    Instant replacementRequestedAt;
    String replacementRequestReason;
    String replacementRequestedBy;
    boolean replacementCompleted;
}
