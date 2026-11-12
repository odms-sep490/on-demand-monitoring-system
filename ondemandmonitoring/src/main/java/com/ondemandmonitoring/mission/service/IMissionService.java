package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.devicecheck.dto.response.PreDeviceCheckResponse;
import com.ondemandmonitoring.mission.dto.request.AssignDeviceRequest;
import com.ondemandmonitoring.mission.dto.request.AssignStaffRequest;
import com.ondemandmonitoring.mission.dto.request.ReplaceDeviceRequest;
import com.ondemandmonitoring.mission.dto.request.RequestDeviceReplacementRequest;
import com.ondemandmonitoring.mission.dto.request.ReplaceStaffRequest;
import com.ondemandmonitoring.mission.dto.request.MissionUpdateRequest;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResourceReplacementResponse;
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

    MissionResponse createAdditionalMission(String orderId, MissionCreateRequest request);

    MissionResponse createResultRevisionReworkMission(String caseId,
            com.ondemandmonitoring.mission.dto.request.CreateResultRevisionReworkMissionRequest request);

    void finalizeReworkPlan(String caseId,
            com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest request);

    MissionResponse confirmMissionPlanning(String missionId);

    /** Opens resource confirmation after the Deposit and Mission payment allocations are ready. */
    List<MissionResponse> transitionToPendingConfirmation(String orderId);

    MissionResponse cancelMission(String missionId, String reason);

    void cancelDraftMission(String missionId);


    MissionResponse updateMission(String missionId, MissionUpdateRequest request);

    MissionResponse rescheduleMission(String missionId,
            com.ondemandmonitoring.mission.dto.request.MissionScheduleUpdateRequest request);

    MissionResponse assignDevice(String missionId, AssignDeviceRequest request);

    MissionResponse removeDevice(String missionId, String assignmentId);

    MissionResponse replaceDevice(String missionId, String assignmentId, ReplaceDeviceRequest request);

    MissionResponse requestDeviceReplacement(String missionId, RequestDeviceReplacementRequest request);

    MissionResponse cancelDeviceReplacementRequest(String missionId, String assignmentId);

    MissionResponse assignStaff(String missionId, AssignStaffRequest request);

    MissionResponse removeStaff(String missionId, String assignmentId);

    MissionResponse replaceStaff(String missionId, String staffId, ReplaceStaffRequest request);

    List<MissionResourceReplacementResponse> getResourceReplacementHistory(String missionId);

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
     * Accepts a mission assignment by the Staff.
     *
     * @param missionId Mission ID
     * @param staffId Staff ID
     * @return updated mission DTO
     */
    MissionResponse acceptMission(String missionId, String staffId);

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
     * Executes digital pre-device safety check before takeoff.
     * Passed -> Sets status to READY_TO_FLY.
     * Failed -> Logs battery/hardware diagnostics, creates maintenance ticket, and
     * auto-swaps device if available.
     *
     * @param missionId Mission ID
     * @param deviceId Device ID/code
     * @return {@link PreDeviceCheckResponse} Preflight diagnostics
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
     * Initiates mission flight (Takeoff).
     *
     * @param missionId Mission ID
     * @return {@link MissionResponse} Mission set to IN_PROGRESS
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
    MissionResponse requestMissionFailure(String missionId, String reason);

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

