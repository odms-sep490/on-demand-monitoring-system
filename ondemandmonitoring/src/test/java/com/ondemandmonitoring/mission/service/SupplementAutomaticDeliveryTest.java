package com.ondemandmonitoring.mission.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class SupplementAutomaticDeliveryTest {
    MissionResultSubmissionFixture f;
    Mission child;
    IMissionService lifecycle;
    @BeforeEach void setup() {
        f = new MissionResultSubmissionFixture(); f.prepare(MissionResultSubmissionFixture.Scenario.INSPECTOR);
        var policy = mock(InspectionCorrectionPolicy.class);
        lifecycle = mock(IMissionService.class);
        ReflectionTestUtils.setField(f.service, "inspectionCorrections", policy);
        ReflectionTestUtils.setField(f.service, "missionLifecycle", lifecycle);
        var correction = new InspectionCorrection(); correction.setMission(f.mission);
        correction.setStatus(InspectionCorrectionStatus.RESOLVED);
        child = new Mission(); child.setId("child"); child.setMissionCode("MS-12345"); child.setInspectionCorrection(correction);
        correction.setSupplementalMission(child);
        when(policy.acceptedSupplement(correction)).thenReturn(child);
        var accepted = new MissionResult(); accepted.setReviewedBy("supplement-inspector");
        when(f.results.findByMissionId("child")).thenReturn(Optional.of(accepted));
    }
    @Test void approvedSupplementCompletesAndPublishesTheReadyOriginalWithItsReviewer() {
        when(f.checklist.isReadyForInspectorApproval(f.mission)).thenReturn(true);
        f.service.completeSupplementedSource(child);
        assertThat(f.result.getApprovalStatus()).isEqualTo(MissionResultApprovalStatus.APPROVED);
        assertThat(f.result.getReviewedBy()).isEqualTo("supplement-inspector");
        assertThat(f.mission.getStatus()).isEqualTo(MissionStatus.COMPLETED);
        verify(lifecycle).completeMissionAfterSupplement(child);
        verify(f.delivery).publishInspectedResults(f.mission.getOrder().getId());
        f.service.completeSupplementedSource(child);
        verify(lifecycle, times(1)).completeMissionAfterSupplement(child);
    }
    @Test void completingOneOriginalLeavesItsSiblingMissionInProgress() {
        var aggregate = f.completion.lockAggregate(f.mission.getOrder().getId());
        var sibling = aggregate.missions().stream().filter(m -> !m.getId().equals(f.mission.getId())).findFirst().orElseThrow();
        when(f.checklist.isReadyForInspectorApproval(f.mission)).thenReturn(true);
        f.service.completeSupplementedSource(child);
        assertThat(f.mission.getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(sibling.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        verify(f.missions, never()).saveAndFlush(sibling);
        verify(f.completion).completeIfReady(aggregate);
    }

    @Test void incompleteOriginalWaitsForRemainingChecklistWork() {
        f.service.completeSupplementedSource(child);
        verifyNoInteractions(lifecycle, f.delivery);
        assertThat(f.result.getApprovalStatus()).isEqualTo(MissionResultApprovalStatus.DRAFT);
    }
    @Test void unresolvedCorrectionCannotTriggerAutomaticDelivery() {
        child.getInspectionCorrection().setStatus(InspectionCorrectionStatus.SUBMITTED);
        assertThatThrownBy(() -> f.service.completeSupplementedSource(child)).isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        verifyNoInteractions(lifecycle, f.delivery);
    }
}
