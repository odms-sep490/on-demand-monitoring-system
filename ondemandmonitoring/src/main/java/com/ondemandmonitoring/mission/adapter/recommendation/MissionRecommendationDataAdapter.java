package com.ondemandmonitoring.mission.adapter.recommendation;

import com.ondemandmonitoring.checklist.repository.ChecklistDeviceRequirementRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.DeviceType;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.recommendation.application.port.MissionRecommendationDataPort;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Mission-owned adapter implementing Recommendation's read-only data port. */
@Component
@RequiredArgsConstructor
public class MissionRecommendationDataAdapter implements MissionRecommendationDataPort {
    private final MissionRepository missions;
    private final MissionChecklistAssignmentRepository checklistAssignments;
    private final ChecklistDeviceRequirementRepository checklistRequirements;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final ResourceTimeLockRepository resourceLocks;
    private final UserScheduleRepository userSchedules;

    @Override
    @Transactional(readOnly = true)
    public MissionScope getScope(String missionId) {
        var mission = missions.findByIdWithOrder(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        var order = mission.getOrder();
        var preferredTime = order.getPreferredTime();
        var activeChecklistAssignments = checklistAssignments
                .findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId);
        Set<String> checklistDefinitionIds = activeChecklistAssignments.stream()
                .map(item -> item.getOrderChecklistItem().getSourceChecklist())
                .filter(java.util.Objects::nonNull)
                .map(item -> item.getId())
                .collect(Collectors.toSet());
        Map<String, List<String>> capabilitiesByDefinition = checklistDefinitionIds.isEmpty() ? Map.of()
                : checklistRequirements.findAllByChecklistIds(checklistDefinitionIds).stream()
                        .collect(Collectors.groupingBy(
                                requirement -> requirement.getChecklistDefinition().getId(),
                                Collectors.mapping(requirement -> requirement.getDeviceType().getCode(), Collectors.toList())));

        List<ChecklistCapability> checklistCapabilities = activeChecklistAssignments.stream()
                .map(assignment -> {
                    OrderChecklistItem item = assignment.getOrderChecklistItem();
                    String definitionId = item.getSourceChecklist() == null ? null : item.getSourceChecklist().getId();
                    List<String> required = definitionId == null ? List.of()
                            : capabilitiesByDefinition.getOrDefault(definitionId, List.of()).stream().distinct().sorted().toList();
                    return new ChecklistCapability(assignment.getOrderChecklistItemId(), item.getContent(), required);
                }).toList();

        long planRevision = order.getExecutionPlan() == null ? 0 : order.getExecutionPlan().getRevision();
        return new MissionScope(
                mission.getId(), mission.getStatus().name(), mission.getCancellationAt() != null,
                planRevision, mission.getScheduleRevision() == null ? 0 : mission.getScheduleRevision(),
                order.getPreferredDateFrom(), order.getPreferredDateTo(),
                preferredTime == null ? null : preferredTime.getStartTime(),
                preferredTime == null ? null : preferredTime.getEndTime(), checklistCapabilities);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceCandidate> findAvailableDevices(String missionId, Instant start, Instant end) {
        return devices.findAvailableForMission(missionId, start, end).stream()
                .map(device -> new DeviceCandidate(
                        device.getId(), device.getDeviceCode(), device.getName(),
                        device.getDeviceModel().getDeviceTypes().stream().map(DeviceType::getCode).distinct().sorted().toList()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int countAvailableStaff(String missionId, Instant start, Instant end) {
        List<User> active = users.findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode.STAFF).stream()
                .filter(user -> user.getRole() != null && user.getRole().isActive()).toList();
        if (active.isEmpty()) return 0;
        List<String> ids = active.stream().map(User::getId).toList();
        Set<String> unavailable = new HashSet<>(resourceLocks.findConflictingResourceIds(ids, missionId, start, end));
        userSchedules.findByStaffIdInAndScheduleTypeAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
                        ids, UserScheduleType.LEAVE, Set.of(UserScheduleStatus.CANCELLED, UserScheduleStatus.COMPLETED), end, start)
                .forEach(schedule -> unavailable.add(schedule.getStaff().getId()));
        return (int) active.stream().filter(user -> !unavailable.contains(user.getId())).count();
    }
}
