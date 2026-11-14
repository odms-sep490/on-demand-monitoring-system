package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.dto.request.MissionResultRequest;
import com.ondemandmonitoring.mission.enums.MissionResultStatus;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.mission.service.MissionResultSubmissionFixture;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class InspectionResultGateTest {
  @Test
  void unresolvedCorrectionPreventsInspectorApprovalCompletionAndPublication() {
    var fixture = new MissionResultSubmissionFixture();
    fixture.prepare(MissionResultSubmissionFixture.Scenario.INSPECTOR);
    var policy = mock(InspectionCorrectionPolicy.class);
    doThrow(new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING))
        .when(policy)
        .requireResolved(fixture.targetId);
    ReflectionTestUtils.setField(fixture.service, "inspectionCorrections", policy);
    var request = new MissionResultRequest();
    request.setStatus(MissionResultStatus.COMPLETED);
    assertThatThrownBy(() -> fixture.service.upsert(fixture.targetId, request))
        .isInstanceOf(ApiException.class);
    verify(fixture.results, never()).saveAndFlush(any());
    verify(fixture.completion, never()).completeIfReady(any());
    verify(fixture.delivery, never()).publishInspectedResults(any());
  }

  @Test
  void resolvedCorrectionRetainsExistingInspectorApprovalPath() {
    var fixture = new MissionResultSubmissionFixture();
    fixture.prepare(MissionResultSubmissionFixture.Scenario.INSPECTOR);
    var policy = mock(InspectionCorrectionPolicy.class);
    ReflectionTestUtils.setField(fixture.service, "inspectionCorrections", policy);
    var request = new MissionResultRequest();
    request.setStatus(MissionResultStatus.COMPLETED);
    fixture.service.upsert(fixture.targetId, request);
    verify(policy).requireResolved(fixture.targetId);
    verify(fixture.delivery).publishInspectedResults("order-1");
  }
}
