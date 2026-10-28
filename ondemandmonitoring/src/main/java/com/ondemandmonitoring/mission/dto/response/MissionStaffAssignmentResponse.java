package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionStaffAssignmentResponse {
    String id;
    String staffId;
    String staffName;
    String staffEmail;
    String staffPhone;
    MissionStaffRole assignedRole;
    MissionStaffAssignmentStatus responseStatus;
    Instant assignedAt;
    Instant respondedAt;
    String declineReason;
}
