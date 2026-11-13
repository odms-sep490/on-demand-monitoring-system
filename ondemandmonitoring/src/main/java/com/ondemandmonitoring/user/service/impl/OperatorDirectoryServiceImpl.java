package com.ondemandmonitoring.user.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.dto.response.AvailableOperatorResponse;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.IOperatorDirectoryService;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OperatorDirectoryServiceImpl implements IOperatorDirectoryService {

    UserRepository userRepository;
    MissionRepository missionRepository;
    ResourceTimeLockRepository resourceTimeLockRepository;
    UserScheduleRepository userScheduleRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AvailableOperatorResponse> getAvailableOperators() {
        return getAvailableOperators(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableOperatorResponse> getAvailableOperators(String missionId) {
        Mission mission = null;
        if (missionId != null && !missionId.isBlank()) {
            mission = missionRepository.findById(missionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND, "Mission not found: " + missionId));
        }
        if (mission != null && (mission.getScheduledStartAt() == null || mission.getScheduledEndAt() == null
                || !mission.getScheduledStartAt().isBefore(mission.getScheduledEndAt()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Mission schedule is required for availability");
        }
        var users = userRepository.findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF).stream()
                .filter(user -> user.getRole() != null && user.getRole().isActive())
                .toList();
        if (users.isEmpty()) return List.of();
        var ids = users.stream().map(User::getId).toList();
        Set<String> conflicts = mission == null ? Set.of() : resourceTimeLockRepository.findConflictingResourceIds(
                ids, mission.getId(), mission.getScheduledStartAt(), mission.getScheduledEndAt());
        if (mission != null) {
            // Operator self-declared busy sessions also make them unavailable for the mission window.
            Set<String> busy = userScheduleRepository
                    .findByStaffIdInAndScheduleTypeAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
                            ids, UserScheduleType.LEAVE,
                            Set.of(UserScheduleStatus.CANCELLED, UserScheduleStatus.COMPLETED),
                            mission.getScheduledEndAt(), mission.getScheduledStartAt())
                    .stream().map(schedule -> schedule.getStaff().getId())
                    .collect(Collectors.toSet());
            if (!busy.isEmpty()) {
                Set<String> merged = new HashSet<>(conflicts);
                merged.addAll(busy);
                conflicts = merged;
            }
        }
        Mission targetMission = mission;
        Set<String> unavailable = conflicts;
        return users.stream()
                .filter(user -> targetMission == null || !unavailable.contains(user.getId()))
                .map(user -> AvailableOperatorResponse.builder()
                        .id(user.getId())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .build())
                .toList();
    }

}
