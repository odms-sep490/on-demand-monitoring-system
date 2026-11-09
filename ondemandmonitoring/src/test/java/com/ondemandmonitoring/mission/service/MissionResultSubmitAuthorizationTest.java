package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.dto.request.MissionResultRequest;
import com.ondemandmonitoring.mission.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ondemandmonitoring.mission.service.MissionResultSubmissionFixture.Scenario;

class MissionResultSubmitAuthorizationTest {
    final MissionResultSubmissionFixture fixture = new MissionResultSubmissionFixture();

    @ParameterizedTest @EnumSource(Scenario.class)
    void directServiceUsesTheSameCanonicalActorPolicy(Scenario scenario) {
        fixture.prepare(scenario);
        assertEquals(scenario.allowed, fixture.authorization.canSubmitMissionResult(fixture.targetId));
        if (scenario.allowed) {
            var response = fixture.service.upsert(fixture.targetId, new MissionResultRequest());
            assertEquals(MissionResultApprovalStatus.APPROVED, response.getApprovalStatus());
            verify(fixture.results).saveAndFlush(fixture.result);
        } else {
            assertError(ErrorCode.ACCESS_DENIED);
            verify(fixture.results, never()).saveAndFlush(any());
        }
        verify(fixture.completion).lockAggregate("order-1");
    }

    @Test void inspectorOwnsTheSingleFinalResultSubmission() {
        fixture.prepare(Scenario.INSPECTOR);
        assertFalse(fixture.authorization.canUploadMissionMedia(fixture.targetId));
        assertTrue(fixture.authorization.canSubmitMissionResult(fixture.targetId));
        fixture.service.upsert(fixture.targetId, new MissionResultRequest());
    }

    @Test void detailedInspectionReportRemainsInNotesInsteadOfTheLimitedReviewNote() {
        fixture.prepare(Scenario.INSPECTOR);
        String report = "Postflight inspection report with checklist and weather details. ".repeat(60);
        var request = new MissionResultRequest();
        request.setSummary("Mission inspection completed");
        request.setNotes(report);

        fixture.service.upsert(fixture.targetId, request);

        assertTrue(report.length() > 1000);
        assertEquals(report, fixture.result.getNotes());
        assertNull(fixture.result.getReviewNote());
        assertTrue(fixture.result.isInspectorConfirmed());
        verify(fixture.delivery).publishInspectedResults("order-1");
    }

    @Test void reportingCrewCannotSubmitTheAcceptancePackage() {
        fixture.prepare(Scenario.OPERATOR);
        assertFalse(fixture.authorization.canSubmitMissionResult(fixture.targetId));
        assertError(ErrorCode.ACCESS_DENIED);
    }

    @Test void pilotCannotSubmitEvenWhenOperatorIsUnavailable() {
        fixture.prepare(Scenario.PILOT_WITH_OPERATOR);
        var operator = fixture.crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.OPERATOR).findFirst().orElseThrow();
        assertFalse(fixture.authorization.canSubmitMissionResult(fixture.targetId));
        operator.setReleaseReason("REPLACED");
        assertFalse(fixture.authorization.canSubmitMissionResult(fixture.targetId));
        assertError(ErrorCode.ACCESS_DENIED);
    }

    @Test void actorAuthorizationDoesNotBypassOperationalCompletion() {
        fixture.prepare(Scenario.INSPECTOR);
        fixture.mission.setCompletedAt(null);
        fixture.mission.setStatus(MissionStatus.COMPLETED);
        assertTrue(fixture.authorization.canSubmitMissionResult(fixture.targetId));
        assertError(ErrorCode.MISSION_STATUS_INVALID);
        verify(fixture.results, never()).saveAndFlush(any());
    }

    @Test void actorAuthorizationDoesNotBypassChecklistOrEvidenceReadiness() {
        fixture.prepare(Scenario.INSPECTOR);
        doThrow(new ApiException(ErrorCode.CHECKLIST_NOT_READY)).when(fixture.checklist).requireReadyForInspectorApproval(fixture.mission);
        assertError(ErrorCode.CHECKLIST_NOT_READY);
        verify(fixture.results, never()).saveAndFlush(any());
    }

    @Test void approvedInspectionReplayIsReadOnlyAndCanRetryPublication() {
        fixture.prepare(Scenario.INSPECTOR);
        fixture.result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        assertEquals("result-1", fixture.service.upsert(fixture.targetId, new MissionResultRequest()).getId());
        verify(fixture.results, never()).saveAndFlush(any());
        verify(fixture.delivery).publishInspectedResults("order-1");
    }

    @Test void correctionUpdatesTheSameResultAndReplayStillChecksAuthorization() {
        fixture.prepare(Scenario.INSPECTOR); fixture.result.setApprovalStatus(MissionResultApprovalStatus.REJECTED);
        assertEquals("result-1", fixture.service.upsert(fixture.targetId, new MissionResultRequest()).getId());
        fixture.service.upsert(fixture.targetId, new MissionResultRequest());
        fixture.actor = fixture.crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.OPERATOR).findFirst().orElseThrow().getStaff();
        assertError(ErrorCode.ACCESS_DENIED);
        verify(fixture.results, times(1)).saveAndFlush(any());
    }

    private void assertError(ErrorCode expected) {
        assertEquals(expected, assertThrows(ApiException.class,
                () -> fixture.service.upsert(fixture.targetId, new MissionResultRequest())).getErrorCode());
    }
}
