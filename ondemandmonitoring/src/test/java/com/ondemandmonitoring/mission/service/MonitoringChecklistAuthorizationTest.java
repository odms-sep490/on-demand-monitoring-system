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
    final com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository publishedEvidence = mock(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class);
    IMissionAuthorizationService policy = new MissionAuthorizationServiceImpl(assignments, missions, resolver, results, com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope(), publishedEvidence);
    Mission mission;
    User actor;

    @ParameterizedTest @EnumSource(MissionStaffRole.class)
    void evidenceCapabilityUsesAssignmentsNotUploadOrGlobalRoleStrings(MissionStaffRole role) {
        mission.setStatus(MissionStatus.IN_PROGRESS);
        when(assignments.findByMissionId("m")).thenReturn(List.of(crew(actor, role)));
        assertEquals(role == MissionStaffRole.OPERATOR || role == MissionStaffRole.PILOT,
                policy.canAttachChecklistEvidence("m"));
        assertEquals(policy.canAttachChecklistEvidence("m"), policy.canDetachChecklistEvidence("m"));
        mission.setStatus(MissionStatus.IN_PROGRESS);
        assertEquals(role == MissionStaffRole.OPERATOR || role == MissionStaffRole.PILOT, policy.canAttachChecklistEvidence("m"));
    }

    @Test void evidenceResultLocksAndFinalReportingCrewArePreserved() {
        mission.setStatus(MissionStatus.COMPLETED); var inspector = crew(actor, MissionStaffRole.OPERATOR);
        inspector.setReleasedAt(Instant.now()); inspector.setReleaseReason("MISSION_COMPLETE");
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector)); assertTrue(policy.canAttachChecklistEvidence("m"));
        var result = new MissionResult(); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        result.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL); assertFalse(policy.canAttachChecklistEvidence("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED); assertFalse(policy.canDetachChecklistEvidence("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.REJECTED); assertTrue(policy.canAttachChecklistEvidence("m"));
        inspector.setReleaseReason("REPLACED"); assertFalse(policy.canAttachChecklistEvidence("m"));
    }

    @BeforeEach void prepare() {
        mission = new Mission(); mission.setId("m"); mission.setStatus(MissionStatus.IN_PROGRESS);
        var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("o");
        order.getExecutionPlan().setStatus(com.ondemandmonitoring.order.enums.ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1); mission.setOrder(order);
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
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
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
        assertEquals(status == MissionStatus.IN_PROGRESS, policy.canExecuteMonitoringChecklist("m"));
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

    @Test void postflightUsesOperatorPrimaryAndInspectorSubmitsAfterCompletion() {
        mission.setStatus(MissionStatus.IN_PROGRESS); mission.setActualEndAt(Instant.now());
        var pilot = crew(actor, MissionStaffRole.PILOT);
        var operator = crew(user("operator"), MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot, operator));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        operator.setReleasedAt(Instant.now()); assertTrue(policy.canExecuteMonitoringChecklist("m"));
        var inspector = crew(actor, MissionStaffRole.INSPECTOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector));
        assertFalse(policy.canExecuteMonitoringChecklist("m")); assertTrue(policy.canAssessMonitoringChecklist("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
        mission.setStatus(MissionStatus.COMPLETED); mission.setCompletedAt(Instant.now());
        inspector.setIsCurrent(false); inspector.setReleasedAt(Instant.now()); inspector.setReleaseReason("MISSION_COMPLETE");
        assertTrue(policy.canSubmitMissionResult("m"));
    }

    @Test void acceptedPilotSupportsUploadButReportsOnlyWithoutAcceptedOperator() {
        mission.setStatus(MissionStatus.IN_PROGRESS);
        var pilot = crew(actor, MissionStaffRole.PILOT);
        var operator = crew(user("operator"), MissionStaffRole.OPERATOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot, operator));
        assertTrue(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertTrue(policy.canAttachChecklistEvidence("m"));
        assertFalse(policy.canCompleteMission("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
        pilot.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void inspectorCannotReviewUntilPostflightAndCannotAttachStaffEvidence() {
        mission.setStatus(MissionStatus.IN_PROGRESS);
        when(assignments.findByMissionId("m"))
                .thenReturn(List.of(crew(actor, MissionStaffRole.INSPECTOR)));

        assertFalse(policy.canReviewMissionMedia("m"));
        mission.setActualEndAt(Instant.now()); assertTrue(policy.canReviewMissionMedia("m"));
        assertFalse(policy.canAttachChecklistEvidence("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
    }

    @Test void readyBlocksUploadsAndInspectorApproval() {
        mission.setStatus(MissionStatus.READY);
        var pilot = crew(actor, MissionStaffRole.PILOT);
        when(assignments.findByMissionId("m")).thenReturn(List.of(pilot));
        assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canCompleteMission("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
        pilot.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(policy.canUploadMissionMedia("m"));
    }

    @Test void readyDoesNotAllowInspectorReviewOrStaffEvidenceChanges() {
        mission.setStatus(MissionStatus.READY);
        when(assignments.findByMissionId("m"))
                .thenReturn(List.of(crew(actor, MissionStaffRole.INSPECTOR)));

        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertFalse(policy.canReviewMissionMedia("m"));
        assertFalse(policy.canAttachChecklistEvidence("m"));
        assertFalse(policy.canSubmitMissionResult("m"));
    }

    @Test void releasedInspectorCanAssessCompletedChecklistAndSubmitResult() {
        mission.setActualEndAt(Instant.now());
        mission.setStatus(MissionStatus.COMPLETED);
        var inspector = crew(actor, MissionStaffRole.INSPECTOR);
        inspector.setReleasedAt(Instant.now());
        inspector.setReleaseReason("MISSION_COMPLETE");
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector));

        assertFalse(policy.canExecuteMonitoringChecklist("m"));
        assertTrue(policy.canReviewMissionMedia("m"));
        assertTrue(policy.canSubmitMissionResult("m"));
    }
    @ParameterizedTest
    @EnumSource(value = MissionResultApprovalStatus.class, names = {"APPROVED", "PENDING_MANAGER_APPROVAL"})
    void frozenResultsBlockReporterAndPilotUploadsEvenAfterSuccessfulCompletion(MissionResultApprovalStatus status) {
        mission.setStatus(MissionStatus.COMPLETED); mission.setCompletedAt(Instant.now());
        var operator = crew(actor, MissionStaffRole.OPERATOR);
        operator.setIsCurrent(false); operator.setReleasedAt(Instant.now()); operator.setReleaseReason("MISSION_COMPLETE");
        when(assignments.findByMissionId("m")).thenReturn(List.of(operator));
        assertTrue(policy.canExecuteMonitoringChecklist("m")); assertTrue(policy.canUploadMissionMedia("m"));
        var result = new MissionResult(); result.setApprovalStatus(status);
        when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        assertFalse(policy.canExecuteMonitoringChecklist("m")); assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canAttachChecklistEvidence("m")); assertFalse(policy.canDetachChecklistEvidence("m"));
        operator.setAssignedRole(MissionStaffRole.PILOT);
        assertFalse(policy.canUploadMissionMedia("m")); assertFalse(policy.canExecuteMonitoringChecklist("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.REJECTED);
        assertTrue(policy.canUploadMissionMedia("m")); assertTrue(policy.canExecuteMonitoringChecklist("m"));
    }

    @Test void inspectorConfirmedApprovalFreezesMediaReviewButPreservesSubmitReplayAndLegacyRecovery() {
        mission.setStatus(MissionStatus.COMPLETED); mission.setActualEndAt(Instant.now()); mission.setCompletedAt(Instant.now());
        var inspector = crew(actor, MissionStaffRole.INSPECTOR);
        inspector.setIsCurrent(false); inspector.setReleasedAt(Instant.now()); inspector.setReleaseReason("MISSION_COMPLETE");
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector));
        var result = new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setInspectorConfirmed(false); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        assertTrue(policy.canReviewMissionMedia("m"), "Legacy manager approval permits pending-media recovery");
        result.setInspectorConfirmed(true);
        assertFalse(policy.canReviewMissionMedia("m")); assertTrue(policy.canSubmitMissionResult("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.REJECTED);
        assertTrue(policy.canReviewMissionMedia("m"));
    }

    @Test void onlyAcceptedPostflightInspectorMayCorrectUnpublishedInspectorConfirmedSelection() {
        var inspector = crew(actor, MissionStaffRole.INSPECTOR);
        when(assignments.findByMissionId("m")).thenReturn(List.of(inspector));
        assertFalse(policy.canSelectCustomerEvidence("m"));
        mission.setActualEndAt(Instant.now()); assertTrue(policy.canSelectCustomerEvidence("m"));
        var result = new MissionResult(); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        for (var status : List.of(MissionResultApprovalStatus.DRAFT, MissionResultApprovalStatus.REJECTED)) {
            result.setApprovalStatus(status); assertTrue(policy.canSelectCustomerEvidence("m"));
        }
        result.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        assertFalse(policy.canSelectCustomerEvidence("m"));
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        assertFalse(policy.canSelectCustomerEvidence("m"), "Legacy approvals stay frozen");
        result.setInspectorConfirmed(true); assertTrue(policy.canSelectCustomerEvidence("m"));
        assertFalse(policy.canExecuteMonitoringChecklist("m")); assertFalse(policy.canUploadMissionMedia("m"));
        assertFalse(policy.canReviewMissionMedia("m")); assertFalse(policy.canAttachChecklistEvidence("m"));
        mission.setStatus(MissionStatus.COMPLETED); mission.setCompletedAt(Instant.now());
        inspector.setIsCurrent(false); inspector.setReleasedAt(Instant.now()); inspector.setReleaseReason("MISSION_COMPLETE");
        assertTrue(policy.canSelectCustomerEvidence("m")); assertTrue(policy.canSubmitMissionResult("m"));
        when(publishedEvidence.existsByMediaAsset_Mission_Id("m")).thenReturn(true);
        assertFalse(policy.canSelectCustomerEvidence("m")); assertTrue(policy.canSubmitMissionResult("m"));
        when(publishedEvidence.existsByMediaAsset_Mission_Id("m")).thenReturn(false);
        inspector.setReleaseReason("REPLACED"); assertFalse(policy.canSelectCustomerEvidence("m"));
        inspector.setReleaseReason("MISSION_COMPLETE"); inspector.setAssignedRole(MissionStaffRole.OPERATOR);
        assertFalse(policy.canSelectCustomerEvidence("m"));
    }

}
