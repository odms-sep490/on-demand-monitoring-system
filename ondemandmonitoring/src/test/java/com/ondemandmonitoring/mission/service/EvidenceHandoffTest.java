package com.ondemandmonitoring.mission.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class EvidenceHandoffTest {
    MissionRepository missions = mock(MissionRepository.class);
    MissionEvidenceSubmissionRepository submissions = mock(MissionEvidenceSubmissionRepository.class);
    MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
    IChecklistEvidenceService evidence = mock(IChecklistEvidenceService.class);
    IMissionChecklistScopeService scope = mock(IMissionChecklistScopeService.class);
    IMissionAuthorizationService auth = mock(IMissionAuthorizationService.class);
    InspectionCorrectionRepository corrections = mock(InspectionCorrectionRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    EvidenceHandoffPolicy policy = new EvidenceHandoffPolicy(submissions, corrections);
    MissionEvidenceSubmissionService service = new MissionEvidenceSubmissionService(missions, submissions,
        executions, evidence, new ChecklistEvidencePolicy(), scope, auth, corrections, policy, users,
        Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC));
    Mission mission = new Mission();
    MissionChecklistExecution execution = new MissionChecklistExecution();
    @BeforeEach void setup() {
        var order = new com.ondemandmonitoring.order.domain.Order();
        order.getExecutionPlan().setStatus(com.ondemandmonitoring.order.enums.ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1L); mission.setOrder(order);
        mission.setId("m"); mission.setStatus(MissionStatus.IN_PROGRESS);
        mission.setActualEndAt(Instant.now()); mission.setCompletedAt(Instant.now());
        execution.setId("e"); execution.setVersion(1L);
        execution.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
        var item = new OrderChecklistItem(); item.setContent("Inspect roof"); execution.setOrderChecklistItem(item);
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(missions.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution));
        when(auth.canViewMission("m")).thenReturn(true);
        when(auth.canAttachChecklistEvidence("m")).thenReturn(true);
        when(evidence.ready(eq(execution), anyList(), eq(false))).thenReturn(true);
        when(users.getCurrentUserId()).thenReturn("pilot");
    }
    MissionEvidenceSubmissionService.Command command() {
        return new MissionEvidenceSubmissionService.Command(UUID.randomUUID(), service.get("m").reviewToken());
    }
    @Test void initialHandoffFreezesOnlyAttachedEvidenceAndRecordsActor() {
        service.submit("m", command());
        verify(submissions).saveAndFlush(argThat(s -> s.getSubmittedBy().equals("pilot")
            && s.getRevision() == 1 && s.getSnapshot().size() == 1));
        verify(scope).requireMaterialized(mission);
    }
    @Test void cannotSubmitBeforePostflightAcceptance() {
        mission.setCompletedAt(null);
        assertThatThrownBy(() -> service.submit("m", command())).isInstanceOf(ApiException.class);
        verify(submissions, never()).saveAndFlush(any());
    }
    @Test void inspectorWithoutReporterAssignmentCannotSubmitDossier() {
        when(auth.canAttachChecklistEvidence("m")).thenReturn(false);
        assertThatThrownBy(() -> service.submit("m", command())).isInstanceOf(ApiException.class);
    }
    @Test void unfinishedChecklistPreventsHandoff() {
        when(evidence.ready(eq(execution), anyList(), eq(false))).thenReturn(false);
        assertThatThrownBy(() -> service.submit("m", command())).isInstanceOf(ApiException.class);
    }
    @Test void everyAttachedFileMustValidateEvenWhenMinimumIsAlreadyMet() {
        var asset = new MediaAsset(); asset.setSourceType("DRONE_CAMERA"); asset.setType("IMAGE");
        asset.setMediaStatus(MediaStatus.VALIDATING); asset.setStorageStatus(MediaStorageStatus.AVAILABLE);
        var link = new MissionChecklistEvidence(); link.setId("link"); link.setMissionChecklistExecution(execution); link.setMediaAsset(asset);
        when(evidence.active("m")).thenReturn(List.of(link));
        assertThatThrownBy(() -> service.submit("m", command())).isInstanceOf(ApiException.class);
        verify(submissions, never()).saveAndFlush(any());
    }
    @Test void staleSnapshotCannotBeSubmitted() {
        var command = command(); execution.setVersion(2L);
        assertThatThrownBy(() -> service.submit("m", command)).isInstanceOf(ApiException.class);
    }
    @Test void correctionUsesItsOwnResubmissionCommand() {
        when(corrections.existsByActiveMissionId("m")).thenReturn(true);
        assertThatThrownBy(() -> service.submit("m", command())).isInstanceOf(ApiException.class);
    }
    @Test void initialInspectionNeedsNoSubmissionAndCorrectionResubmissionLocksEvidence() {
        assertThat(policy.editable("m")).isTrue(); assertThat(policy.reviewable("m")).isTrue();
        when(submissions.findFirstByMission_IdOrderByRevisionDesc("m")).thenReturn(Optional.of(new MissionEvidenceSubmission()));
        assertThat(policy.editable("m")).isTrue(); assertThat(policy.reviewable("m")).isTrue();
        var correction = new InspectionCorrection(); correction.setStatus(InspectionCorrectionStatus.OPEN);
        when(corrections.findByMission_IdOrderByRequestedAtDescIdDesc("m")).thenReturn(List.of(correction));
        assertThat(policy.editable("m")).isTrue(); assertThat(policy.reviewable("m")).isFalse();
        correction.setStatus(InspectionCorrectionStatus.SUBMITTED);
        assertThat(policy.editable("m")).isFalse(); assertThat(policy.reviewable("m")).isTrue();
        correction.setStatus(InspectionCorrectionStatus.RESOLVED);
        assertThat(policy.editable("m")).isFalse(); assertThat(policy.reviewable("m")).isTrue();
    }
    @Test void initialInspectionDoesNotRequireCrewHandoff() {
        assertThatCode(() -> policy.requireReviewable("m")).doesNotThrowAnyException();
    }
}
