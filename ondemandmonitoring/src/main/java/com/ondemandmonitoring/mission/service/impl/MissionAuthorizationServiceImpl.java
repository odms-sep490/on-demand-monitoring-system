package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;
import com.ondemandmonitoring.mission.repository.ControlHandoverRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("missionAuthorizationService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionAuthorizationServiceImpl implements IMissionAuthorizationService {
    private static final Set<MissionStatus> TERMINAL_STATUSES = Set.of(
            MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW, MissionStatus.COMPLETED,
            MissionStatus.FAILED, MissionStatus.CANCELLED);
    private static final Set<MissionStatus> PILOT_UPLOAD_STATUSES = Set.of(
            MissionStatus.IN_PROGRESS);
    private static final Set<MissionStaffRole> REQUIRED_OPERATIONAL_CREW = Set.of(
            MissionStaffRole.PILOT, MissionStaffRole.OPERATOR,
            MissionStaffRole.MAINTAINER, MissionStaffRole.INSPECTOR);
    private final MissionStaffAssignmentRepository assignments;
    private final MissionRepository missions;
    private final AuthenticatedUserResolver currentUser;
    private final com.ondemandmonitoring.mission.repository.MissionResultRepository results;
    private final com.ondemandmonitoring.mission.service.IMissionChecklistScopeService checklistScope;
    @Autowired
    private MissionDeviceAssignmentRepository deviceAssignments;
    @Autowired
    private ControlHandoverRepository handovers;
    @Autowired
    private MissionWeatherCheckRepository weatherChecks;

    @Override
    public boolean canAttachChecklistEvidence(String missionId) {
        return findRuntimeMissionById(missionId).filter(m -> PILOT_UPLOAD_STATUSES.contains(m.getStatus()))
                .filter(m -> results.findByMissionId(m.getId()).map(r -> r.getApprovalStatus() != com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.APPROVED
                        && r.getApprovalStatus() != com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL).orElse(true))
                .map(m -> hasEligibleInspectorAssignment(m, currentUser.getCurrentUser())
                        || hasEligiblePilotAssignment(m, currentUser.getCurrentUser())
                        || canExecuteAsReportingCrew(m, currentUser.getCurrentUser()))
                .orElse(false);
    }

    @Override
    public boolean canDetachChecklistEvidence(String missionId) { return canAttachChecklistEvidence(missionId); }

    @Override
    public boolean canManageMissions() {
        User user = currentUser.getCurrentUser();
        return hasRole(user, RoleCode.MANAGER) || hasRole(user, RoleCode.ADMIN);
    }

    @Override
    public boolean canViewStaffMissions(String staffId) {
        User user = currentUser.getCurrentUser();
        return canManageMissions() || (hasRole(user, RoleCode.STAFF) && user.getId().equals(staffId));
    }

    @Override
    public boolean canViewMission(String missionId) {
        Optional<Mission> mission = findMissionById(missionId);
        if (mission.isEmpty()) return false;
        if (canManageMissions()) return true;
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        if (!ownAssignments(mission.get().getId(), user).isEmpty()) return true;
        return assignments.existsByMissionIdAndStaffId(mission.get().getId(), user.getId());
    }

    @Override
    public boolean canRespondToMission(String missionId, String staffId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF) || !user.getId().equals(staffId)) return false;
        return findRuntimeMissionById(missionId).filter(mission ->
                mission.getStatus() == MissionStatus.PLANNING)
                .map(mission -> ownAssignments(mission.getId(), user).stream()
                        .anyMatch(entry -> entry.getResponseStatus() == MissionStaffAssignmentStatus.PENDING))
                .orElse(false);
    }

    @Override
    public boolean canRespondToMission(String missionId) {
        return canRespondToMission(missionId, currentUser.getCurrentUserId());
    }

    @Override
    public boolean canStartMission(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF) || deviceAssignments == null || handovers == null) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.READY)
                .map(mission -> {
                    List<MissionStaffAssignment> crew = assignments
                            .findAllByMissionIdAndIsCurrentTrue(mission.getId()).stream()
                            .filter(entry -> entry.getStaff() != null
                                    && Boolean.TRUE.equals(entry.getStaff().getIsActive())
                                    && entry.getReleasedAt() == null)
                            .toList();
                    Set<MissionStaffRole> acceptedRoles = crew.stream()
                            .filter(entry -> entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                            .map(MissionStaffAssignment::getAssignedRole)
                            .collect(java.util.stream.Collectors.toSet());
                    boolean inspector = crew.stream().anyMatch(entry ->
                            entry.getAssignedRole() == MissionStaffRole.INSPECTOR
                                    && entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED
                                    && user.getId().equals(entry.getStaff().getId()));
                    boolean mainDeviceReady = deviceAssignments
                            .findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
                                    mission.getId(), DeviceRole.MAIN)
                            .stream()
                            .anyMatch(assignment -> assignment.getReleasedAt() == null
                                    && assignment.getDevice() != null
                                    && assignment.getStatus() == MissionDeviceAssignmentStatus.READY_TO_FLY);
                    boolean controlHandedOver = handovers
                            .findTopByStaffAssignment_Mission_IdAndStatusOrderByConfirmedAtDesc(
                                    mission.getId(), "CONFIRMED")
                            .isPresent();
                    return inspector && mainDeviceReady && controlHandedOver
                            && acceptedRoles.containsAll(Set.of(MissionStaffRole.PILOT,
                                    MissionStaffRole.OPERATOR, MissionStaffRole.MAINTAINER,
                                    MissionStaffRole.INSPECTOR))
                            && crew.stream().allMatch(entry ->
                                    entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED);
                }).orElse(false);
    }

    @Override
    public boolean canCancelMission(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.READY
                        && mission.getActualStartAt() == null
                        && mission.getActualEndAt() == null)
                .filter(mission -> !latestWeatherWasAccepted(mission.getId()))
                .filter(mission -> hasEligibleInspectorAssignment(mission, user))
                .isPresent();
    }

    private boolean latestWeatherWasAccepted(String missionId) {
        return weatherChecks != null
                && weatherChecks.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                        .map(check -> Boolean.TRUE.equals(check.getInspectorAccepted()))
                        .orElse(false);
    }

    @Override
    public boolean canControlFlight(String missionId) {
        return normalOperationAllowed(missionId) && canPerform(missionId, MissionStaffRole.PILOT);
    }

    private boolean normalOperationAllowed(String missionId) {
        return findRuntimeMissionById(missionId).isPresent();
    }

    @Override
    public boolean canOperatePayload(String missionId) {
        return findRuntimeMissionById(missionId)
                .filter(this::crewAcceptedBeforePreflight)
                .filter(mission -> mission.getActualEndAt() == null)
                .map(mission -> canPerform(mission.getId(), MissionStaffRole.OPERATOR))
                .orElse(false);
    }

    /** Keep device connection and preflight closed until the full assigned crew accepts. */
    private boolean crewAcceptedBeforePreflight(Mission mission) {
        if (mission.getStatus() != MissionStatus.PLANNING) return true;
        List<MissionStaffAssignment> crew = assignments.findByMissionId(mission.getId()).stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getIsCurrent()) && entry.getReleasedAt() == null)
                .toList();
        Set<MissionStaffRole> acceptedRoles = crew.stream()
                .filter(entry -> entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .map(MissionStaffAssignment::getAssignedRole)
                .collect(java.util.stream.Collectors.toSet());
        return !crew.isEmpty()
                && crew.stream().allMatch(entry ->
                        entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                && acceptedRoles.containsAll(REQUIRED_OPERATIONAL_CREW);
    }

    @Override
    public boolean canExecuteMonitoringChecklist(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.IN_PROGRESS)
                .map(mission -> {
                    return hasEligiblePilotAssignment(mission, user)
                            || canExecuteAsReportingCrew(mission, user);
                }).orElse(false);
    }

    private boolean canExecuteAsReportingCrew(Mission mission, User user) {
        if (!hasRole(user, RoleCode.STAFF)) return false;
        boolean completed = mission.getStatus() == MissionStatus.COMPLETED || mission.getCompletedAt() != null;
        // Operational completion releases current assignments. Only the final accepted crew
        // released by successful completion can finish the business report afterwards.
        List<MissionStaffAssignment> usable = assignments.findByMissionId(mission.getId()).stream()
                .filter(entry -> entry.getStaff() != null && hasRole(entry.getStaff(), RoleCode.STAFF))
                .filter(entry -> entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .filter(entry -> completed
                        ? entry.getReleasedAt() != null && "MISSION_COMPLETE".equals(entry.getReleaseReason())
                        : Boolean.TRUE.equals(entry.getIsCurrent()) && entry.getReleasedAt() == null)
                .toList();
        MissionStaffRole required = usable.stream().anyMatch(entry ->
                entry.getAssignedRole() == MissionStaffRole.OPERATOR)
                ? MissionStaffRole.OPERATOR : MissionStaffRole.PILOT;
        return usable.stream().anyMatch(entry -> entry.getAssignedRole() == required
                && user.getId().equals(entry.getStaff().getId()));
    }

    @Override
    public boolean canInspectDevice(String missionId) {
        return canPerform(missionId, MissionStaffRole.INSPECTOR);
    }

    @Override
    public boolean canAssessMonitoringChecklist(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getActualEndAt() != null)
                .filter(mission -> mission.getStatus() == MissionStatus.IN_PROGRESS
                        || mission.getStatus() == MissionStatus.COMPLETED)
                .map(mission -> hasEligibleInspectorAssignment(mission, user))
                .orElse(false);
    }

    @Override
    public boolean canMaintainDevice(String missionId) {
        // Postflight records actualEndAt before inspector acceptance. Keep the assigned
        // maintainer authorized while the mission is still active; canPerform rejects
        // terminal missions, including those already submitted for manager review.
        return findRuntimeMissionById(missionId)
                .map(mission -> canPerform(missionId, MissionStaffRole.MAINTAINER))
                .orElse(false);
    }

    @Override
    public boolean canViewMissionMedia(String missionId) {
        return canViewMission(missionId);
    }

    @Override
    public boolean canUploadMissionMedia(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .map(mission -> (mission.getStatus() == MissionStatus.IN_PROGRESS
                                && mission.getActualEndAt() != null
                                && hasEligibleInspectorAssignment(mission, user))
                        || (PILOT_UPLOAD_STATUSES.contains(mission.getStatus())
                                && hasEligiblePilotAssignment(mission, user)))
                .orElse(false);
    }

    @Override
    public boolean canReviewMissionMedia(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.IN_PROGRESS
                        && mission.getActualEndAt() != null)
                .map(mission -> hasEligibleInspectorAssignment(mission, user))
                .orElse(false);
    }

    @Override
    public boolean canCompleteMission(String missionId) {
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.IN_PROGRESS
                        && mission.getActualEndAt() != null && mission.getCompletedAt() == null)
                .map(mission -> hasEligibleInspectorAssignment(mission, currentUser.getCurrentUser())).orElse(false);
    }

    @Override
    public boolean canSubmitMissionResult(String missionId) {
        // The inspector owns the final acceptance package. Pilot/operator finish the flight
        // and upload data; the inspector reviews everything and submits once to the manager.
        return findRuntimeMissionById(missionId)
                .filter(mission -> (mission.getStatus() == MissionStatus.IN_PROGRESS
                            && mission.getCompletedAt() != null)
                        || mission.getStatus() == MissionStatus.COMPLETED)
                .map(mission -> hasEligibleInspectorAssignment(mission, currentUser.getCurrentUser()))
                .orElse(false);
    }

    private boolean canPerform(String missionId, MissionStaffRole task) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId).filter(mission -> mission.getStatus() != MissionStatus.WAITING_DEPOSIT)
                .filter(mission -> !TERMINAL_STATUSES.contains(mission.getStatus()))
                .map(mission -> {
                    List<MissionStaffAssignment> crew = assignments.findAllByMissionIdAndIsCurrentTrue(mission.getId());
                    return crew.stream().anyMatch(entry -> entry.getStaff() != null
                            && user.getId().equals(entry.getStaff().getId())
                            && Boolean.TRUE.equals(entry.getIsCurrent()) && entry.getReleasedAt() == null
                            && entry.getAssignedRole() == task
                            && entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED);
                }).orElse(false);
    }

    private List<MissionStaffAssignment> ownAssignments(String missionId, User user) {
        return assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(missionId, user.getId())
                .stream().filter(entry -> Boolean.TRUE.equals(entry.getIsCurrent()) && entry.getReleasedAt() == null)
                .toList();
    }

    private boolean hasEligibleInspectorAssignment(Mission mission, User user) {
        return hasEligibleAssignment(mission, user, MissionStaffRole.INSPECTOR);
    }

    private boolean hasEligiblePilotAssignment(Mission mission, User user) {
        return hasEligibleAssignment(mission, user, MissionStaffRole.PILOT);
    }

    private boolean hasEligibleAssignment(Mission mission, User user, MissionStaffRole role) {
        return assignments.findByMissionId(mission.getId()).stream()
                .anyMatch(entry -> entry.getStaff() != null
                        && user.getId().equals(entry.getStaff().getId())
                        && hasRole(entry.getStaff(), RoleCode.STAFF)
                        && entry.getAssignedRole() == role
                        && entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED
                        && Boolean.TRUE.equals(entry.getIsCurrent())
                        && (mission.getStatus() == MissionStatus.COMPLETED || mission.getCompletedAt() != null
                            ? entry.getReleasedAt() != null && "MISSION_COMPLETE".equals(entry.getReleaseReason())
                            : entry.getReleasedAt() == null));
    }

    private Optional<Mission> findMissionById(String missionId) {
        return missions.findById(missionId);
    }

    private Optional<Mission> findRuntimeMissionById(String missionId) {
        return findMissionById(missionId).filter(MissionRuntimeGate::isStructurallyFinalized).filter(checklistScope::isMaterialized);
    }

    @Override
    public boolean canViewMissionByCode(String missionCode) {
        return missions.findByMissionCode(missionCode)
                .map(mission -> canViewMission(mission.getId())).orElse(false);
    }

    private boolean hasRole(User user, RoleCode role) {
        return Boolean.TRUE.equals(user.getIsActive()) && user.getRole() != null
                && user.getRole().isActive() && user.getRole().getCode() == role;
    }
}
