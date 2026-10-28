package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MissionAuthorizationServiceTest {
    private final MissionStaffAssignmentRepository assignments = mock(MissionStaffAssignmentRepository.class);
    private final MissionRepository missions = mock(MissionRepository.class);
    private final AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    private final IMissionAuthorizationService policy = new MissionAuthorizationServiceImpl(assignments, missions, resolver, mock(com.ondemandmonitoring.mission.repository.MissionResultRepository.class), com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope());
    private User staff;
    private Mission mission;

    @BeforeEach
    void setup() {
        staff = User.builder().role(Role.builder().code(RoleCode.STAFF).active(true).build()).isActive(true).build();
        staff.setId("staff-1");
        mission = new Mission();
        mission.setId("mission-1");
        mission.setStatus(MissionStatus.SCHEDULED);
        when(resolver.getCurrentUser()).thenReturn(staff);
        when(resolver.getCurrentUserId()).thenReturn(staff.getId());
        when(missions.findById(mission.getId())).thenReturn(Optional.of(mission));
    }

    @ParameterizedTest
    @EnumSource(MissionStaffRole.class)
    void flightControlRequiresAcceptedPilotAssignment(MissionStaffRole role) {
        assign(role, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canControlFlight("mission-1")).isEqualTo(role == MissionStaffRole.PILOT);
        assertThat(policy.canControlFlight("another-mission")).isFalse();
    }

    @Test
    void pendingCrewCanRespondButCannotExecuteOrRespondOnBehalfOfAnotherUser() {
        mission.setStatus(MissionStatus.READY);
        assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.PENDING);
        assertThat(policy.canRespondToMission("mission-1")).isTrue();
        assertThat(policy.canRespondToMission("mission-1", "staff-2")).isFalse();
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        mission.setStatus(MissionStatus.IN_FLIGHT);
        assertThat(policy.canRespondToMission("mission-1")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(com.ondemandmonitoring.mission.enums.MissionSafeStopStatus.class)
    void everySafeStopPhaseDisablesNormalFlightAndPayload(com.ondemandmonitoring.mission.enums.MissionSafeStopStatus phase) {
        mission.setStatus(MissionStatus.READY_TO_FLY);
        mission.setSafeStopStatus(phase);
        assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assertThat(policy.canTerminateFlight("mission-1")).isFalse(); // no request timestamp: fail closed
        assign(MissionStaffRole.OPERATOR, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canOperatePayload("mission-1")).isFalse();
    }

    @Test
    void requestedPilotCanOnlyTerminateAndConfirmedCleanedCancelledCannotFly() {
        mission.setStatus(MissionStatus.IN_FLIGHT);
        mission.setSafeStopRequestedAt(Instant.now());
        mission.setSafeStopStatus(com.ondemandmonitoring.mission.enums.MissionSafeStopStatus.REQUESTED);
        assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assertThat(policy.canTerminateFlight("mission-1")).isTrue();
        assertThat(policy.canTerminateFlight("another-mission")).isFalse();
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        mission.setSafeStopConfirmedAt(Instant.now());
        assertThat(policy.canTerminateFlight("mission-1")).isFalse();
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        mission.setSafeStopCleanedAt(Instant.now());
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        mission.setStatus(MissionStatus.CANCELLED);
        assertThat(policy.canTerminateFlight("mission-1")).isFalse();
    }

    @Test
    void terminationRemainsPilotOnlyAndMaintainerCanFinishInspection() {
        mission.setStatus(MissionStatus.RETURNING);
        mission.setSafeStopRequestedAt(Instant.now());
        assign(MissionStaffRole.OPERATOR, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canTerminateFlight("mission-1")).isFalse();
        assertThat(policy.canOperatePayload("mission-1")).isFalse();
        assign(MissionStaffRole.MAINTAINER, MissionStaffAssignmentStatus.ACCEPTED);
        mission.setStatus(MissionStatus.POSTFLIGHT_CHECKING);
        assertThat(policy.canMaintainDevice("mission-1")).isTrue();
    }

    @Test
    void historicalConfirmedReadyCanOnlyPreparePostflightNotReopenFlight() {
        mission.setStatus(MissionStatus.READY_TO_FLY); mission.setSafeStopRequestedAt(Instant.now());
        mission.setSafeStopConfirmedAt(Instant.now());
        mission.setSafeStopStatus(com.ondemandmonitoring.mission.enums.MissionSafeStopStatus.IN_PROGRESS);
        assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assertThat(policy.canTerminateFlight("mission-1")).isFalse();
        assertThat(policy.canPrepareSafeStopPostflight("mission-1")).isTrue();
        mission.setSafeStopCleanedAt(Instant.now());
        assertThat(policy.canPrepareSafeStopPostflight("mission-1")).isFalse();
    }

    @Test
    void crewPermissionsStayScopedToAssignedMissionRole() {
        var pilot = assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canControlFlight("mission-1")).isTrue();
        assertThat(policy.canInspectDevice("mission-1")).isFalse();
        assertThat(policy.canOperatePayload("mission-1")).isFalse();

        var inspector = MissionStaffAssignment.builder().staff(staff).assignedRole(MissionStaffRole.INSPECTOR)
                .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(true).build();
        when(assignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(List.of(inspector));
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assertThat(policy.canInspectDevice("mission-1")).isTrue();
        assertThat(policy.canOperatePayload("mission-1")).isFalse();

        var operator = MissionStaffAssignment.builder().staff(staff).assignedRole(MissionStaffRole.OPERATOR)
                .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(true).build();
        when(assignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(List.of(operator));
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assertThat(policy.canInspectDevice("mission-1")).isFalse();
        assertThat(policy.canOperatePayload("mission-1")).isTrue();
    }

    @Test
    void oneStaffWithAllAcceptedRolesCanPerformTheFullMissionFlow() {
        var allRoles = java.util.Arrays.stream(MissionStaffRole.values())
                .map(role -> MissionStaffAssignment.builder().staff(staff).mission(mission).assignedRole(role)
                        .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(true).build())
                .toList();
        when(assignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(allRoles);
        when(assignments.findByMissionId("mission-1")).thenReturn(allRoles);
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("mission-1", "staff-1"))
                .thenReturn(allRoles);

        assertThat(policy.canOperatePayload("mission-1")).isTrue();
        assertThat(policy.canControlFlight("mission-1")).isTrue();
        assertThat(policy.canMaintainDevice("mission-1")).isTrue();

        mission.setStatus(MissionStatus.PENDING_REVIEW);
        assertThat(policy.canUploadMissionMedia("mission-1")).isTrue();
        assertThat(policy.canCompleteMission("mission-1")).isTrue();
        assertThat(policy.canSubmitMissionResult("mission-1")).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "STAFF", mode = EnumSource.Mode.EXCLUDE)
    void systemRoleDoesNotBypassCrewPermissions(RoleCode role) {
        assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        staff.getRole().setCode(role);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assertThat(policy.canMaintainDevice("mission-1")).isFalse();
        assertThat(policy.canManageMissions()).isEqualTo(role == RoleCode.ADMIN || role == RoleCode.MANAGER);
    }

    @Test
    void inactiveReleasedRejectedAndTerminalAssignmentsCannotMutateMission() {
        var assignment = assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        assignment.setReleasedAt(Instant.now());
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assignment.setReleasedAt(null);
        assignment.setIsCurrent(false);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assignment.setIsCurrent(true);
        assignment.setResponseStatus(MissionStaffAssignmentStatus.REJECTED);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        assignment.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);
        staff.setIsActive(false);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        staff.setIsActive(true);
        mission.setStatus(MissionStatus.COMPLETED);
        assertThat(policy.canControlFlight("mission-1")).isFalse();
    }

    @Test
    void historicalCrewCanReadButCannotControlAndOnlyInspectorCanFinishUploads() {
        mission.setStatus(MissionStatus.COMPLETED);
        var entry = MissionStaffAssignment.builder().staff(staff).assignedRole(MissionStaffRole.INSPECTOR)
                .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(true)
                .releasedAt(Instant.now()).releaseReason("MISSION_COMPLETE").build();
        when(assignments.existsByMissionIdAndStaffId("mission-1", "staff-1")).thenReturn(true);
        when(assignments.findByMissionId("mission-1")).thenReturn(List.of(entry));
        assertThat(policy.canViewMission("mission-1")).isTrue();
        assertThat(policy.canUploadMissionMedia("mission-1")).isTrue();
        assertThat(policy.canControlFlight("mission-1")).isFalse();
        entry.setAssignedRole(MissionStaffRole.OPERATOR);
        assertThat(policy.canUploadMissionMedia("mission-1")).isFalse();
    }

    @Test
    void staffCannotQueryAnotherStaffsMissions() {
        assertThat(policy.canViewStaffMissions("staff-1")).isTrue();
        assertThat(policy.canViewStaffMissions("staff-2")).isFalse();
        assertThat(policy.canViewMission("mission-1")).isFalse();
    }

    @Test
    void historicalCrewCanStillReadNonTerminalMissionForReviewPermissions() {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        var entry = MissionStaffAssignment.builder().staff(staff).mission(mission).assignedRole(MissionStaffRole.INSPECTOR)
                .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(false)
                .releasedAt(Instant.now()).releaseReason("MISSION_COMPLETE").build();
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("mission-1", "staff-1"))
                .thenReturn(List.of());
        when(assignments.existsByMissionIdAndStaffId("mission-1", "staff-1")).thenReturn(true);
        when(assignments.findByMissionId("mission-1")).thenReturn(List.of(entry));

        assertThat(policy.canViewMission("mission-1")).isTrue();
    }

    private MissionStaffAssignment assign(MissionStaffRole role, MissionStaffAssignmentStatus response) {
        var entry = MissionStaffAssignment.builder().staff(staff).mission(mission).assignedRole(role)
                .responseStatus(response).isCurrent(true).build();
        when(assignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(List.of(entry));
        when(assignments.findByMissionId("mission-1")).thenReturn(List.of(entry));
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("mission-1", "staff-1"))
                .thenReturn(List.of(entry));
        return entry;
    }

    @Test
    void siblingsShareOrderContextButNeverMissionPermissions() {
        var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("order-1");
        mission.setOrder(order);
        var sibling = new Mission(); sibling.setId("mission-2"); sibling.setOrder(order);
        sibling.setStatus(MissionStatus.PENDING_REVIEW);
        when(missions.findById("mission-2")).thenReturn(Optional.of(sibling));
        var crew = java.util.Arrays.stream(MissionStaffRole.values()).map(role ->
                MissionStaffAssignment.builder().mission(mission).staff(staff).assignedRole(role)
                        .isCurrent(true).responseStatus(MissionStaffAssignmentStatus.ACCEPTED).build()).toList();
        when(assignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(crew);
        when(assignments.findByMissionId("mission-1")).thenReturn(crew);
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("mission-1", staff.getId()))
                .thenReturn(crew);
        assertThat(policy.canViewMission("mission-1")).isTrue();
        assertThat(policy.canViewMission("mission-2")).isFalse();
        assertThat(policy.canControlFlight("mission-1")).isTrue();
        assertThat(policy.canControlFlight("mission-2")).isFalse();
        assertThat(policy.canOperatePayload("mission-1")).isTrue();
        assertThat(policy.canOperatePayload("mission-2")).isFalse();
        assertThat(policy.canInspectDevice("mission-1")).isTrue();
        assertThat(policy.canInspectDevice("mission-2")).isFalse();
        assertThat(policy.canMaintainDevice("mission-1")).isTrue();
        assertThat(policy.canMaintainDevice("mission-2")).isFalse();
        mission.setStatus(MissionStatus.IN_FLIGHT);
        assertThat(policy.canExecuteMonitoringChecklist("mission-1")).isTrue();
        assertThat(policy.canExecuteMonitoringChecklist("mission-2")).isFalse();
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        assertThat(policy.canAttachChecklistEvidence("mission-1")).isTrue();
        assertThat(policy.canAttachChecklistEvidence("mission-2")).isFalse();
        assertThat(policy.canDetachChecklistEvidence("mission-2")).isFalse();
        assertThat(policy.canUploadMissionMedia("mission-1")).isTrue();
        assertThat(policy.canUploadMissionMedia("mission-2")).isFalse();
        assertThat(policy.canCompleteMission("mission-1")).isTrue();
        assertThat(policy.canCompleteMission("mission-2")).isFalse();
        assertThat(policy.canSubmitMissionResult("mission-1")).isTrue();
        assertThat(policy.canSubmitMissionResult("mission-2")).isFalse();
        var orders = mock(com.ondemandmonitoring.order.repository.OrderRepository.class);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId())).thenReturn(List.of(mission, sibling));
        var orderPolicy = new com.ondemandmonitoring.order.service.impl.OrderAuthorizationService(
                orders, resolver, policy, missions);
        assertThat(orderPolicy.canViewOrder(order.getId())).isTrue();
        assertThat(policy.canViewMission(order.getId())).isFalse();
        assertThat(policy.canCompleteMission(order.getId())).isFalse();
        assertThat(policy.canViewMission("mission-2")).isFalse();
    }

    @Test
    void codeLookupIsExplicitAndCannotAliasTheMissionIdContract() {
        when(missions.findByMissionCode("MS-001")).thenReturn(Optional.of(mission));
        assign(MissionStaffRole.PILOT, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canViewMission("MS-001")).isFalse();
        assertThat(policy.canViewMissionByCode("MS-001")).isTrue();
        assertThat(policy.canViewMissionByCode("missing")).isFalse();
    }

    @Test
    void inspectorUploadAndCompletionRequireCurrentOrSuccessfulFinalAssignment() {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        var entry = assign(MissionStaffRole.INSPECTOR, MissionStaffAssignmentStatus.ACCEPTED);
        assertThat(policy.canUploadMissionMedia("mission-1")).isTrue();
        assertThat(policy.canCompleteMission("mission-1")).isTrue();
        assertThat(policy.canSubmitMissionResult("mission-1")).isTrue();
        assertThat(policy.canUploadMissionMedia("another-mission")).isFalse();
        entry.setIsCurrent(false);
        assertThat(policy.canUploadMissionMedia("mission-1")).isFalse();
        assertThat(policy.canCompleteMission("mission-1")).isFalse();
        entry.setIsCurrent(true); entry.setReleasedAt(Instant.now()); entry.setReleaseReason("REPLACED");
        assertThat(policy.canUploadMissionMedia("mission-1")).isFalse();
        mission.setStatus(MissionStatus.COMPLETED);
        assertThat(policy.canUploadMissionMedia("mission-1")).isFalse();
        entry.setReleaseReason("MISSION_COMPLETE");
        assertThat(policy.canUploadMissionMedia("mission-1")).isTrue();
        assertThat(policy.canCompleteMission("mission-1")).isFalse();
        assertThat(policy.canSubmitMissionResult("mission-1")).isTrue();
        entry.setIsCurrent(false);
        assertThat(policy.canUploadMissionMedia("mission-1")).isFalse();
        entry.setIsCurrent(true); staff.setIsActive(false);
        assertThat(policy.canUploadMissionMedia("mission-1")).isFalse();
    }
}
