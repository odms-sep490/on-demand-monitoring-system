package com.ondemandmonitoring.devicecheck.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck;
import com.ondemandmonitoring.devicecheck.dto.response.PreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.mapper.PreDeviceCheckCompletionMapper;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.IPreDeviceCheckCompletionService;
import com.ondemandmonitoring.weather.domain.MissionWeatherCheck;
import com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository;
import com.ondemandmonitoring.mission.domain.FlightToken;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IFlightTokenService;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PreDeviceCheckCompletionServiceImpl implements IPreDeviceCheckCompletionService {

    private final MissionRepository missionRepository;
    private final MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    private final MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    private final PersistedPreDeviceCheckRepository preDeviceCheckRepository;
    private final MissionWeatherCheckRepository missionWeatherCheckRepository;
    private final IFlightTokenService flightTokenService;
    private final PreDeviceCheckCompletionMapper preDeviceCheckCompletionMapper;

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public PreDeviceCheckResponse complete(String missionId, String deviceId) {
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionId));
        com.ondemandmonitoring.mission.service.impl.MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        if (mission.getStatus() != MissionStatus.CONNECTED
                && mission.getStatus() != MissionStatus.PREFLIGHT_CHECKING
                && mission.getStatus() != MissionStatus.READY_TO_FLY) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Mission is not eligible for preflight completion");
        }
        Device assignedDevice = getCurrentDevice(mission.getId());
        if (assignedDevice == null || assignedDevice.getId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Mission has no assigned deviceId");
        }
        if (deviceId == null || deviceId.isBlank() || !assignedDevice.getId().equals(deviceId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Device does not match the mission assignment");
        }

        String staffId = getAcceptedPilotId(mission.getId());
        if (staffId == null || staffId.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Mission chưa có pilot nhận nhiệm vụ để được cấp quyền bay.");
        }

        PersistedPreDeviceCheck persistedPreDeviceCheck = requirePassedPersistedPreDevice(mission.getId());
        FlightToken token = flightTokenService.issueFlightToken(mission.getId(), assignedDevice.getId(), staffId);
        mission.setStatus(MissionStatus.READY_TO_FLY);
        missionRepository.save(mission);
        MissionWeatherCheck weatherCheck = missionWeatherCheckRepository
                .findFirstByMissionIdOrderByCreatedAtDesc(mission.getId())
                .orElse(null);

        return preDeviceCheckCompletionMapper.toResponse(mission, assignedDevice, persistedPreDeviceCheck, token, weatherCheck);
    }

    private PersistedPreDeviceCheck requirePassedPersistedPreDevice(String missionId) {
        PersistedPreDeviceCheck run = preDeviceCheckRepository
                .findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "No persisted pre-device run found. Run the real pre-device checklist first."));
        if (run.getStatus() != PreDeviceCheckStatus.PASSED) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Latest persisted pre-device is not passed: " + run.getStatus());
        }
        boolean hasNonPassingItem = run.getItems().stream()
                .filter(item -> PersistedPreDeviceCheckService.isActiveCheckType(item.getCheckType()))
                .anyMatch(item -> Boolean.TRUE.equals(item.getRequired())
                        && item.getStatus() != PreDeviceItemStatus.PASSED);
        if (hasNonPassingItem) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Every required pre-flight item must pass before a flight token can be issued.");
        }
        return run;
    }

    private Device getCurrentDevice(String missionId) {
        return missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .map(MissionDeviceAssignment::getDevice)
                .orElse(null);
    }

    private String getAcceptedPilotId(String missionId) {
        return missionStaffAssignmentRepository
                .findAllByMissionIdAndAssignedRoleAndIsCurrentTrue(missionId,
                        MissionStaffRole.PILOT)
                .stream()
                .filter(entry -> entry.getResponseStatus() ==
                        MissionStaffAssignmentStatus.ACCEPTED)
                .findFirst()
                .map(assignment -> assignment.getStaff() != null && assignment.getStaff().getId() != null
                        ? assignment.getStaff().getId().toString()
                        : null)
                .orElse(null);
    }
}
