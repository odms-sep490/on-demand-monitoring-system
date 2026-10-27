package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.devicecheck.dto.response.PreDeviceCheckResponse;
import com.ondemandmonitoring.mission.dto.request.AssignDeviceRequest;
import com.ondemandmonitoring.mission.dto.request.AssignStaffRequest;
import com.ondemandmonitoring.mission.dto.request.MissionUpdateRequest;
import com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionTelemetryReadinessResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.dto.request.MissionCreateRequest;
import org.springframework.data.domain.Pageable;
import java.time.Instant;

import java.util.List;

/**
 * Application service interface for mission lifecycle and assignment
 * orchestration (Flow 3).
 * Returns DTOs (MissionResponse) to decouple domain entities from
 * presentation/controller layers.
 */
public interface IMissionService {

    MissionResponse createMission(String orderId, MissionCreateRequest request);


    MissionResponse replaceMission(String missionId, MissionCreateRequest request);
    MissionResponse finalizeReplacementMission(String missionId);

    MissionResponse updateMission(String missionId, MissionUpdateRequest request);

    MissionResponse updateFlightPermit(String missionId, FlightPermitUpdateRequest request);

    MissionResponse assignDevice(String missionId, AssignDeviceRequest request);

    MissionResponse removeDevice(String missionId, com.ondemandmonitoring.mission.dto.request.RemoveDeviceRequest request);

    MissionResponse assignStaff(String missionId, AssignStaffRequest request);

    MissionResponse removeStaff(String missionId, com.ondemandmonitoring.mission.dto.request.RemoveStaffRequest request);

    List<MissionResponse> getAllMissions();
    /**
     * Retrieves detailed mission information by ID as a DTO.
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Mission details DTO
     */
    MissionResponse getByIdResponse(String missionId);

    /**
     * Retrieves mission information by mission code.
     *
     * @param missionCode Unique mission code
     * @return {@link MissionResponse} Mission details DTO
     */
    MissionResponse getByCodeResponse(String missionCode);


    /**
     * Retrieves all missions assigned to a specific Staff.
     *
     * @param staffId Assigned Staff ID
     * @return List of {@link MissionResponse}
     */
    List<MissionResponse> getByStaffId(String staffId);

    List<MissionResponse> getCurrentStaffMissions();

    PageResponse<MissionResponse> searchStaffMissions(MissionStatus status, Instant from, Instant toExclusive,
            Pageable pageable);


    /**
     * Retrieves all missions pending resource assignment (device/Staff).
     *
     * @return List of {@link MissionResponse}
     */
    List<MissionResponse> getPendingAssignmentMissions();

    /**
     * Retrieves the mission flight plan and waypoints.
     *
     * @param missionId Mission ID
     * @return {@link MissionPlanResponse} Flight plan details
     */
    MissionPlanResponse getMissionPlan(String missionId);

    MissionResponse acceptCurrentStaffMission(String missionId);

    MissionResponse rejectCurrentStaffMission(String missionId, String reason);

    MissionResponse handoverCurrentStaffControl(String missionId);

    /**
     * Finds and returns the raw Mission domain entity by ID.
     *
     * @param missionId Mission ID
     * @return {@link Mission} Entity
     */
    Mission findById(String missionId);

    /**
     * Accepts a mission assignment by the Staff (transitions status to SCHEDULED
     * if flight plan is feasible).
     *
     * @param missionId  Mission ID
     * @param staffId Staff ID
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse acceptMission(String missionId, String staffId);

    /** Inspector starts the operational workflow after every required crew role accepted. */
    MissionResponse startMissionWorkflow(String missionId);

    /**
     * Rejects a mission assignment by the Staff (resets mission to
     * PLANNING for re-assignment).
     *
     * @param missionId  Mission ID
     * @param staffId Staff ID
     * @param reason     Rejection reason
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse rejectMission(String missionId, String staffId, String reason);


//    MissionResponse assignResources(String missionId, String deviceId, String staffId);

    /**
     * Connects GCS app for the mission (Delegated to IGcsConnectionService).
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse connectGcs(String missionId);

    /**
     * Disconnects GCS session cleanly (Delegated to IGcsConnectionService).
     *
     * @param missionId        Mission ID
     * @param disconnectReason Disconnect reason
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse disconnectGcs(String missionId, String disconnectReason);

    /**
     * Handles GCS session signal loss and triggers RTL (Delegated to
     * IGcsConnectionService).
     *
     * @param missionId Mission ID
     * @param reason    Cause of signal loss
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse handleGcsSessionLost(String missionId, String reason);

    /**
     * Executes digital pre-device safety check before takeoff.
     * Passed -> Issues 60-minute FlightToken and sets status to READY_TO_FLY.
     * Failed -> Logs battery/hardware diagnostics, creates maintenance ticket, and
     * auto-swaps device if available.
     *
     * @param missionId Mission ID
     * @param deviceId Device ID/code
     * @return {@link PreDeviceCheckResponse} Preflight diagnostics and issued
     *         flight token (if passed)
     */
    PreDeviceCheckResponse runPreDeviceCheck(String missionId, String deviceId);

    MissionTelemetryReadinessResponse getTelemetryReadiness(String missionId);


    /**
     * Hands over mission control to a new Staff (Control Handover).
     *
     * @param missionId  Mission ID
     * @param staffId Target Staff ID
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse handoverControl(String missionId, String staffId);

    /**
     * Initiates mission flight (Takeoff) with token validation.
     *
     * @param missionId  Mission ID
     * @param tokenValue Flight token string
     * @return {@link MissionResponse} Mission set to IN_FLIGHT
     */
    MissionResponse startMission(String missionId, String tokenValue);

    /**
     * Initiates mission flight (Takeoff).
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Mission set to IN_FLIGHT
     */
    MissionResponse startMission(String missionId);

    /**
     * Marks the device as returning to base (Return-To-Launch).
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Mission set to RETURNING
     */
    MissionResponse markReturning(String missionId);

    /**
     * Starts postflight health inspection for the mission.
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Mission set to POST_FLIGHT
     */
    MissionResponse startPostDeviceChecking(String missionId);

    /**
     * Completes the mission successfully.
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Mission set to COMPLETED
     */
    MissionResponse completeMission(String missionId);

    /**
     * Marks mission as failed due to technical or environmental issues.
     *
     * @param missionId Mission ID
     * @param reason    Failure cause
     * @return {@link MissionResponse} Mission set to FAILED
     */
    MissionResponse failMission(String missionId, String reason);

    /**
     * Updates postflight status and device health notes.
     *
     * @param missionId       Mission ID
     * @param newDeviceStatus New device status (e.g., AVAILABLE, MAINTENANCE)
     * @param notes           Diagnostic notes
     * @return {@link MissionResponse} Updated mission DTO
     */
    MissionResponse updatePostFlightStatus(String missionId, DeviceStatus newDeviceStatus, String notes);

    MissionResponse recordPostFlightInspection(
            String missionId,
            DeviceStatus newDeviceStatus,
            String notes,
            java.util.Map<String, com.ondemandmonitoring.mission.enums.InspectionResult> results,
            com.ondemandmonitoring.mission.dto.request.PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot);

}

