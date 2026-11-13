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
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionDeviceCheckStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
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
        MissionDeviceAssignment assignment = missionDeviceAssignmentRepository
                .findByMissionIdAndDeviceIdAndIsCurrentTrue(mission.getId(), deviceId)
                .filter(a -> a.getReleasedAt() == null).orElse(null);
        Device assignedDevice = assignment == null ? null : assignment.getDevice();
        if (assignedDevice == null || assignedDevice.getId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Mission has no assigned deviceId");
        }
        boolean alreadyReady = mission.getStatus() == MissionStatus.READY
                && assignment.getStatus() == MissionDeviceAssignmentStatus.READY_TO_FLY
                && assignment.getCheckupStatus() == MissionDeviceCheckStatus.PASSED;
        if (mission.getStatus() != MissionStatus.PENDING_CONFIRMATION && !alreadyReady) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Preflight completion requires PENDING_CONFIRMATION, or an already-passed READY mission.");
        }
        if (deviceId == null || deviceId.isBlank() || !assignedDevice.getId().equals(deviceId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Device does not match the mission assignment");
        }

        if (!hasAcceptedOperator(mission.getId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission chưa có Operator nhận nhiệm vụ để hoàn tất kiểm tra trước chuyến bay.");
        }

        PersistedPreDeviceCheck persistedPreDeviceCheck = requirePassedPersistedPreDevice(mission.getId(), assignment.getId());
        if (assignment.getStatus() != MissionDeviceAssignmentStatus.PREFLIGHT_CHECKING
                && assignment.getStatus() != MissionDeviceAssignmentStatus.READY_TO_FLY) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "The assigned device must complete its preflight inspection before takeoff.");
        }
        assignment.setStatus(MissionDeviceAssignmentStatus.READY_TO_FLY);
        assignment.setCheckupStatus(MissionDeviceCheckStatus.PASSED);
        missionDeviceAssignmentRepository.save(assignment);
        MissionWeatherCheck weatherCheck = missionWeatherCheckRepository
                .findFirstByMissionIdOrderByCreatedAtDesc(mission.getId())
                .orElse(null);

        return preDeviceCheckCompletionMapper.toResponse(mission, assignedDevice, persistedPreDeviceCheck, weatherCheck);
    }

    private PersistedPreDeviceCheck requirePassedPersistedPreDevice(String missionId, String assignmentId) {
        PersistedPreDeviceCheck run = preDeviceCheckRepository
                .findByMissionIdOrderByCreatedAtDesc(missionId).stream()
                .filter(candidate -> candidate.getMissionDeviceAssignment() != null && assignmentId.equals(candidate.getMissionDeviceAssignment().getId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "No persisted pre-device run found. Run the real pre-device checklist first."));
        if (run.getStatus() == PreDeviceCheckStatus.CANCELLED) {
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
                    "Tất cả hạng mục kiểm tra trước bay bắt buộc phải đạt thì nhiệm vụ mới được chuyển sang trạng thái sẵn sàng bay.");
        }
        return run;
    }

    private MissionDeviceAssignment getCurrentMainAssignment(String missionId) {
        return missionDeviceAssignmentRepository
                .findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(missionId, DeviceRole.MAIN)
                .stream().findFirst().orElse(null);
    }

    private boolean hasAcceptedOperator(String missionId) {
        return missionStaffAssignmentRepository
                .findAllByMissionIdAndAssignedRoleAndIsCurrentTrue(missionId,
                        MissionStaffRole.OPERATOR)
                .stream()
                .anyMatch(assignment -> assignment.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED
                        && assignment.getReleasedAt() == null
                        && Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getStaff() != null
                        && assignment.getStaff().getId() != null);
    }
}
