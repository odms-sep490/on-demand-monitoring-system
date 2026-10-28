package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MediaType;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionResponse {

    @Schema(description = "Unique identifier of the mission", example = "550e8400-e29b-41d4-a716-446655440000")
    String id;

    @Schema(description = "Unique code of the mission", example = "MS-2026-0001")
    String missionCode;

    @Schema(description = "Mission lifecycle status", example = "PLANNING")
    MissionStatus status;

    MissionResourcePrepareStatus resourcePrepareStatus;

    FlightPermitStatus flightPermitStatus;
    String flightPermitNote;
    String flightPermitFileName;
    String flightPermitContentType;
    String flightPermitDataUrl;

    com.ondemandmonitoring.mission.enums.MissionSafeStopStatus safeStopStatus;
    Instant safeStopRequestedAt;
    Instant safeStopConfirmedAt;
    Instant safeStopCleanedAt;

    @Schema(description = "ID of the associated order", example = "123e4567-e89b-12d3-a456-426614174000")
    String orderId;

    String orderCode;

    // Persisted relation only; no sibling detail lookup or inherited runtime history.
    String replacesMissionId;

    String orderTitle;

    LocalDate orderPreferredDateFrom;

    LocalDate orderPreferredDateTo;

    String orderPreferredTimeName;

    String serviceName;

    String customerName;

    String managerName;

    String managerEmail;

    String managerPhone;

    String description;

    String address;

    Double latitude;

    Double longitude;

    Double radiusM;

    String deviceId;

    String deviceCode;

    String deviceName;

    String deviceSerialNumber;

    String deviceStatus;

    String deviceModelCode;

    String deviceModelName;

    String deviceManufacturer;

    String devicePayload;

    String staffId;

    String operatorId;

    List<MissionStaffAssignmentResponse> staffAssignments;

    List<MissionDeviceAssignmentResponse> deviceAssignments;

    MissionPlanResponse plan;

    @Schema(description = "Scheduled start timestamp")
    Instant scheduledStartAt;

    @Schema(description = "Scheduled end timestamp")
    Instant scheduledEndAt;

    @Schema(description = "Actual start timestamp")
    Instant actualStartAt;

    @Schema(description = "Actual end timestamp")
    Instant actualEndAt;

    @Schema(description = "Reason for mission failure, if any")
    String failureReason;

    String rejectionReason;

    MediaType mediaType;

    String mediaSummary;

    @Schema(description = "Creation timestamp")
    Instant createdAt;

    @Schema(description = "Last update timestamp")
    Instant updatedAt;
}
