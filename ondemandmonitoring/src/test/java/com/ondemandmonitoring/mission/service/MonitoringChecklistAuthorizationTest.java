package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl;
import com.ondemandmonitoring.role.domain.*;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringChecklistAuthorizationTest {
    MissionRepository missions = mock(MissionRepository.class);
    MissionStaffAssignmentRepository assignments = mock(MissionStaffAssignmentRepository.class);
    AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    MissionResultRepository results = mock(MissionResultRepository.class);
    IMissionAuthorizationService policy = new MissionAuthorizationServiceImpl(assignments, missions, resolver, results, com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope());
    Mission mission;
    User actor;

    @ParameterizedTest @EnumSource(MissionStaffRole.class)
    void evidenceCapabilityUsesAssignmentsNotUploadOrGlobalRoleStrings(MissionStaffRole role) {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, role)));
        assertEquals(role == MissionStaffRole.INSPECTOR || role == MissionStaffRole.PILOT,
                policy.canAttachChecklistEvidence("m"));
        assertEquals(policy.canAttachChecklistEvidence("m"), policy.canDetachChecklistEvidence("m"));
        mission.setStatus(MissionStatus.IN_FLIGHT);
        assertEquals(role == MissionStaffRole.INSPECTOR || role == MissionStaffRole.PILOT, policy.canAttachChecklistEvidence("m"));
    }

    @Test void evidenceResultLocksAndFinalReportingCrewArePreserved() {
        mission.setStatus(MissionStatus.COMPLETED); var inspector = crew(actor, MissionStaffRole.INSPECTOR);
        inspector.setReleasedAt(Instant.now()); inspector.setReleaseReason("MISSION_COMPLETE");
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector)); assertTrue(policy.canAttachChecklistEvidence("m"));
        var result = new MissionResult(); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        result.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL); assertFalse(policy.canAttachChecklistEvidence("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED); assertFalse(policy.canDetachChecklistEvidence("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.REJECTED); assertTrue(policy.canAttachChecklistEvidence("m"));
        inspector.setReleaseReason("REPLACED"); assertFalse(policy.canAttachChecklistEvidence("m"));
    }

    @BeforeEach void prepare() {
        mission = new Mission(); mission.setId("m"); mission.setStatus(MissionStatus.IN_FLIGHT);
        actor = user("actor");
        when(resolver.getCurrentUser()).thenReturn(actor);
        when(missions.findById("m")).thenReturn(Optional.of(mission));
    }

    User user(String id) {
        var user = User.builder().isActive(true).role(Role.builder().code(RoleCode.STAFF).active(true).build()).build();
        user.setId(id); return user;
    }

    MissionStaffAssignment crew(User user, MissionStaffRole role) {
        return MissionStaffAssignment.builder().staff(user).assignedRole(role).isCurrent(true)
                .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).build();
    }

    @ParameterizedTest @EnumSource(MissionStaffRole.class)
    void operatorAndPilotCanUpdateChecklistWhileOtherCrewCannot(MissionStaffRole role) {
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, role)));
        assertEquals(role == MissionStaffRole.OPERATOR || role == MissionStaffRole.PILOT,
                policy.canExecuteMonitoringChecklist("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("other"));
    }

    @ParameterizedTest @EnumSource(MissionStaffAssignmentStatus.class)
    void operatorMustAccept(MissionStaffAssignmentStatus response) {
        var operator = crew(actor, MissionStaffRole.OPERATOR); operator.setResponseStatus(response);
        when(assignments.findByMissionId("m")).thenReturn(List.of(operator));
        assertEquals(response == MissionStaffAssignmentStatus.ACCEPTED, policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void pilotFallbackRequiresNoUsableOperator() {
        var pilot = crew(actor, MissionStaffRole.PILOT);
        var operator = crew(user("operator"), MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot, operator));
        assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setResponseStatus(MissionStaffAssignmentStatus.PENDING); assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setResponseStatus(MissionStaffAssignmentStatus.REJECTED); assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);
        operator.getStaff().setIsActive(false); assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.getStaff().setIsActive(true);
        operator.setReleasedAt(Instant.now()); assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setReleasedAt(null); operator.setIsCurrent(false); assertTrue(policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void inactiveReleasedNonCurrentAndUnassignedActorsDenied() {
        var operator = crew(actor, MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(operator));
        actor.setIsActive(false); assertFalse(policy.canExecuteMonitoringChecklist("m"));
        actor.setIsActive(true); actor.getRole().setActive(false); assertFalse(policy.canExecuteMonitoringChecklist("m"));
        actor.getRole().setActive(true); operator.setIsCurrent(false); assertFalse(policy.canExecuteMonitoringChecklist("m"));
        operator.setIsCurrent(true); operator.setReleasedAt(Instant.now()); assertFalse(policy.canExecuteMonitoringChecklist("m"));
        when(assignments.findByMissionId("m")).thenReturn(List.of()); assertFalse(policy.canExecuteMonitoringChecklist("m"));
    }

    @ParameterizedTest @EnumSource(value = RoleCode.class, names = "STAFF", mode = EnumSource.Mode.EXCLUDE)
    void systemRolesCannotBecomeExecutionActors(RoleCode role) {
        actor.getRole().setCode(role);
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, MissionStaffRole.OPERATOR)));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
    }

    @ParameterizedTest @EnumSource(MissionStatus.class)
    void mutationWindowIsExplicit(MissionStatus status) {
        mission.setStatus(status);
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, MissionStaffRole.OPERATOR)));
        assertEquals(Set.of(MissionStatus.READY_TO_FLY, MissionStatus.IN_FLIGHT,
                MissionStatus.IN_PROGRESS, MissionStatus.RETURNING,
                MissionStatus.POSTFLIGHT_CHECKING, MissionStatus.PENDING_REVIEW).contains(status), policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void successfulFinalCrewCanFinishAfterOperationalRelease() {
        mission.setStatus(MissionStatus.COMPLETED);
        var operator = crew(actor, MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(operator));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        operator.setIsCurrent(false); operator.setReleasedAt(Instant.now()); operator.setReleaseReason("MISSION_COMPLETE");
        assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setIsCurrent(true); assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setReleaseReason("REPLACED"); assertFalse(policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void pendingReviewLetsPilotUpdateProgressAndInspectorSubmit() {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        var pilot = crew(actor, MissionStaffRole.PILOT);
        var operator = crew(user("operator"), MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot, operator));
        assertTrue(policy.canExecuteMonitoringChecklist("m"));
        operator.setReleasedAt(Instant.now());
        assertTrue(policy.canExecuteMonitoringChecklist("m"));
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, MissionStaffRole.OPERATOR)));
        assertTrue(policy.canExecuteMonitoringChecklist("m"));
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, MissionStaffRole.INSPECTOR)));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertTrue(policy.canSubmitMissionResult("m"));
    }

    @Test void acceptedPilotCanUploadAndUpdateChecklistWithoutCompletingOrSubmitting() {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        var pilot = crew(actor, MissionStaffRole.PILOT);
        var operator = crew(user("operator"), MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot, operator));
        assertTrue(policy.canUploadMissionMedia("m"));
        assertTrue(policy.canExecuteMonitoringChecklist("m"));
        assertTrue(policy.canAttachChecklistEvidence("m"));
        assertFalse(policy.canCompleteMission("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
        pilot.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void inspectorReviewsAndAssignsEvidenceWhilePilotIsFlying() {
        mission.setStatus(MissionStatus.IN_FLIGHT);
        when(assignments.findByMissionId("m"))
                .thenReturn(List.of(crew(actor, MissionStaffRole.INSPECTOR)));

        assertTrue(policy.canReviewMissionMedia("m"));
        assertTrue(policy.canAttachChecklistEvidence("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
    }

    @Test void readyToFlyAllowsAcceptedPilotUploadButNotInspectorApproval() {
        mission.setStatus(MissionStatus.READY_TO_FLY);
        var pilot = crew(actor, MissionStaffRole.PILOT);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot));
        assertTrue(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canCompleteMission("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
        pilot.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(policy.canUploadMissionMedia("m"));
    }

    @Test void readyToFlyLetsInspectorOpenAndUpdateAcceptanceChecklist() {
        mission.setStatus(MissionStatus.READY_TO_FLY);
        when(assignments.findByMissionId("m"))
                .thenReturn(List.of(crew(actor, MissionStaffRole.INSPECTOR)));

        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertTrue(policy.canReviewMissionMedia("m"));
        assertTrue(policy.canAttachChecklistEvidence("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
    }

    @Test void releasedInspectorCanAssessCompletedChecklistAndSubmitResult() {
        mission.setStatus(MissionStatus.COMPLETED);
        var inspector = crew(actor, MissionStaffRole.INSPECTOR);
        inspector.setReleasedAt(Instant.now());
        inspector.setReleaseReason("MISSION_COMPLETE");
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector));

        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertTrue(policy.canReviewMissionMedia("m"));
        assertTrue(policy.canSubmitMissionResult("m"));
    }
}
