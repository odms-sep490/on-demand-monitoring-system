package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
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
    private final MissionStaffAssignmentRepository assignments;
    private final MissionRepository missions;
    private final AuthenticatedUserResolver currentUser;
    private final com.ondemandmonitoring.mission.repository.MissionResultRepository results;
    private final com.ondemandmonitoring.mission.service.IMissionChecklistScopeService checklistScope;
    private final com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository publishedEvidence;
    @Autowired
    private MissionDeviceAssignmentRepository deviceAssignments;
    @Autowired
    private ControlHandoverRepository handovers;
    @Autowired
    private MissionWeatherCheckRepository weatherChecks;
    @Autowired
    private PersistedPostDeviceCheckRepository postDeviceChecks;

    @Override
    public boolean canAttachChecklistEvidence(String missionId) {
        return findRuntimeMissionById(missionId).filter(this::reportingStage)
                .filter(this::reportEditable)
                .map(m -> hasEligiblePilotAssignment(m, currentUser.getCurrentUser())
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
                mission.getStatus() == MissionStatus.PENDING_CONFIRMATION)
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

    @Override
    public boolean canContinueRequestedFailurePostflight(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.FAILED
                        && mission.getFailureRequestedAt() != null
                        && mission.getActualEndAt() == null)
                .filter(mission -> hasCurrentMainDeviceAssignmentIn(mission,
                        MissionDeviceAssignmentStatus.RETURNING,
                        MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING))
                .map(mission -> hasEligiblePilotAssignment(mission, user))
                .orElse(false);
    }

    private boolean normalOperationAllowed(String missionId) {
        return findRuntimeMissionById(missionId).isPresent();
    }

    @Override
    public boolean canOperatePayload(String missionId) {
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getActualEndAt() == null)
                .map(mission -> canPerform(mission.getId(), MissionStaffRole.OPERATOR))
                .orElse(false);
    }

    @Override
    public boolean canExecuteMonitoringChecklist(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(this::reportingStage)
                .filter(this::reportEditable)
                .map(mission -> canExecuteAsReportingCrew(mission, user)).orElse(false);
    }

    private boolean reportEditable(Mission mission) {
        return results.findByMissionId(mission.getId()).map(result ->
                result.getApprovalStatus() != com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.APPROVED
                        && result.getApprovalStatus() != com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL)
                .orElse(true);
    }

    private boolean reportingStage(Mission mission) {
        return mission.getStatus() == MissionStatus.IN_PROGRESS || mission.getStatus() == MissionStatus.COMPLETED;
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
    public boolean canSelectCustomerEvidence(String missionId) {
        if (!canAssessMonitoringChecklist(missionId)
                || publishedEvidence.existsByMediaAsset_Mission_Id(missionId)) return false;
        return results.findByMissionId(missionId).map(result ->
                result.getApprovalStatus() == com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.DRAFT
                        || result.getApprovalStatus() == com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.REJECTED
                        || result.getApprovalStatus() == com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.APPROVED
                            && result.isInspectorConfirmed()).orElse(true);
    }

    @Override
    public boolean canMaintainDevice(String missionId) {
        // Keep the assigned maintainer authorized through the postflight run, including
        // a flight failure that was recorded before the maintenance inspection completed.
        return findRuntimeMissionById(missionId)
                .map(mission -> canPerform(missionId, MissionStaffRole.MAINTAINER)
                        || canMaintainFailedPostflight(mission))
                .orElse(false);
    }

    private boolean canMaintainFailedPostflight(Mission mission) {
        User user = currentUser.getCurrentUser();
        return mission.getStatus() == MissionStatus.FAILED
                && mission.getFailureRequestedAt() != null
                && hasCurrentMainDeviceAssignmentIn(mission,
                        MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING,
                        MissionDeviceAssignmentStatus.COMPLETED)
                && hasEligibleAssignment(mission, user, MissionStaffRole.MAINTAINER);
    }

    private boolean hasCurrentMainDeviceAssignmentIn(
            Mission mission, MissionDeviceAssignmentStatus... statuses) {
        Set<MissionDeviceAssignmentStatus> allowedStatuses = Set.of(statuses);
        return deviceAssignments
                .findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
                        mission.getId(), DeviceRole.MAIN)
                .stream()
                .anyMatch(assignment -> assignment.getReleasedAt() == null
                        && allowedStatuses.contains(assignment.getStatus()));
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
                .filter(this::reportingStage)
                .filter(this::reportEditable)
                .map(mission -> hasEligiblePilotAssignment(mission, user)
                        || canExecuteAsReportingCrew(mission, user))
                .orElse(false);
    }

    @Override
    public boolean canReviewMissionMedia(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF)) return false;
        return findRuntimeMissionById(missionId)
                .filter(this::reportingStage)
                .filter(mission -> mission.getActualEndAt() != null)
                .filter(mission -> results.findByMissionId(mission.getId())
                        .map(result -> result.getApprovalStatus() != com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.APPROVED
                                || !result.isInspectorConfirmed()).orElse(true))
                .map(mission -> hasEligibleInspectorAssignment(mission, user))
                .orElse(false);
    }

    @Override
    public boolean canCompleteMission(String missionId) {
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.IN_PROGRESS
                        && mission.getActualEndAt() != null && mission.getCompletedAt() == null
                        && mission.getFailureRequestedAt() == null)
                .map(mission -> hasEligibleInspectorAssignment(mission, currentUser.getCurrentUser())).orElse(false);
    }

    @Override
    public boolean canConfirmMissionFailure(String missionId) {
        User user = currentUser.getCurrentUser();
        if (!hasRole(user, RoleCode.STAFF) || postDeviceChecks == null) return false;
        return findRuntimeMissionById(missionId)
                .filter(mission -> mission.getStatus() == MissionStatus.IN_PROGRESS
                        && mission.getFailureRequestedAt() != null
                        && mission.getActualEndAt() != null
                        && mission.getCompletedAt() == null)
                .filter(mission -> hasCurrentMainDeviceAssignmentIn(
                        mission, MissionDeviceAssignmentStatus.COMPLETED))
                .filter(mission -> postDeviceChecks
                        .findFirstByMissionIdOrderByCreatedAtDesc(mission.getId())
                        .map(run -> run.getStatus() == DeviceCheckStatus.PASSED
                                || run.getStatus() == DeviceCheckStatus.FAILED)
                        .orElse(false))
                .map(mission -> hasEligibleInspectorAssignment(mission, user))
                .orElse(false);
    }

    @Override
    public boolean canFailMission(String missionId) {
        return findRuntimeMissionById(missionId)
                .map(mission -> mission.getFailureRequestedAt() != null
                        ? canConfirmMissionFailure(missionId)
                        : canControlFlight(missionId) || canMaintainDevice(missionId))
                .orElse(false);
    }

    @Override
    public boolean canSubmitMissionResult(String missionId) {
        // The inspector owns the final acceptance package. Pilot/operator finish the flight
        // and upload data; the inspector reviews and confirms the final customer package.
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
        return findRuntimeMissionById(missionId).filter(mission -> mission.getStatus() != MissionStatus.PENDING_PAYMENT)
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
                        && (mission.getStatus() == MissionStatus.COMPLETED || mission.getCompletedAt() != null
                            ? entry.getReleasedAt() != null && "MISSION_COMPLETE".equals(entry.getReleaseReason())
                            : Boolean.TRUE.equals(entry.getIsCurrent()) && entry.getReleasedAt() == null));
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
