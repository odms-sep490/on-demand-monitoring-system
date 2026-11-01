package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.dto.response.MissionMediaContext;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MissionMediaAccessServiceImpl implements IMissionMediaAccessService {
    MissionRepository missions;
    MissionDeviceAssignmentRepository deviceAssignments;
    AuthenticatedUserResolver currentUser;
    IMissionAuthorizationService authorization;

    @Override
    public void requireUploadPermission(String identifier) {
        if (!authorization.canUploadMissionMedia(identifier)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "An accepted Pilot or Inspector assignment is required to upload media");
        }
    }

    @Override
    public MissionMediaContext authorizeOperator(String identifier) {
        Mission mission = requireMission(identifier);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        if (!authorization.canViewMissionMedia(mission.getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Operator is not assigned to mission");
        }
        return new MissionMediaContext(mission.getId(),
                authorization.canUploadMissionMedia(mission.getId()), mission.getStatus(),
                mission.getScheduledStartAt(), mission.getScheduledEndAt());
    }

    @Override
    public void requireAssignedDevice(String missionId, String deviceId) {
        // Independently authorize the public boundary, even when called outside media.
        authorizeOperator(missionId);
        Mission mission = requireMission(missionId);
        boolean assigned = deviceAssignments.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId())
                .stream()
                .anyMatch(entry -> entry.getDevice() != null && deviceId.equals(entry.getDevice().getId()));
        if (!assigned && mission.getStatus() == MissionStatus.COMPLETED) {
            assigned = deviceAssignments.findByMissionId(mission.getId()).stream()
                    .anyMatch(entry -> deviceId.equals(entry.getDevice().getId()));
        }
        if (!assigned) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Device is not assigned to mission");
        }
    }

    @Override
    public String authorizeCustomer(String identifier) {
        Mission mission = requireMission(identifier);
        String userId = currentUser.getCurrentUserId();
        if (mission.getOrder() == null || mission.getOrder().getCustomer() == null
                || !userId.equals(mission.getOrder().getCustomer().getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return mission.getId();
    }

    @Override
    public MissionDeviceAssignment requireDeviceAssignment(String missionId, String deviceId) {
        requireAssignedDevice(missionId, deviceId);
        return deviceAssignments.findByMissionIdAndDeviceIdAndIsCurrentTrue(missionId, deviceId)
                .or(() -> missions.findById(missionId).filter(m -> m.getStatus() == MissionStatus.COMPLETED)
                    .flatMap(m -> deviceAssignments.findByMissionId(missionId).stream()
                        .filter(a -> a.getDevice() != null && deviceId.equals(a.getDevice().getId())
                                && a.getReleasedAt() != null && "MISSION_COMPLETE".equals(a.getReleaseReason()))
                        .max(java.util.Comparator.comparing(MissionDeviceAssignment::getReleasedAt))))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Device does not have a current assignment to this mission"));
    }

    @Override
    public List<String> ownCustomerMissionIds() {
        return missions.findByOrder_Customer_Id(currentUser.getCurrentUserId()).stream()
                .map(Mission::getId).toList();
    }

    private Mission requireMission(String identifier) {
        return missions.findById(identifier).or(() -> missions.findByMissionCode(identifier))
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    }
}
