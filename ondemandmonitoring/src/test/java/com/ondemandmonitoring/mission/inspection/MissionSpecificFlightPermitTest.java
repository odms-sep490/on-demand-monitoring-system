package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest;
import com.ondemandmonitoring.user.domain.User;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionSpecificFlightPermitTest {
    FlightPermitLifecycleGateTest f = new FlightPermitLifecycleGateTest();
    @BeforeEach void setup() {
        f.setup();
        var pilot = new User(); pilot.setId("pilot");
        f.pilot.setStaff(pilot); f.pilot.setAssignedRole(MissionStaffRole.PILOT);
        f.pilot.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);
        when(f.missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(List.of(f.mission));
    }
    void upload(FlightPermitStatus status) {
        f.service.updateFlightPermit("mission", new FlightPermitUpdateRequest(status, "Mission permit", "permit.pdf", "application/pdf", "data:application/pdf;base64,AQ=="));
    }
    void inherited() {
        var permit = f.mission.getFlightPermit();
        permit.setStatus(FlightPermitStatus.APPROVED); permit.setReusedFromMissionId("original");
        permit.setPilotStaffIds("pilot"); permit.setDataUrl("data:application/pdf;base64,AA==");
    }
    @ParameterizedTest
    @ValueSource(strings = {"ORIGINAL", "CUSTOMER_REWORK", "INTERNAL_CORRECTION"})
    void everyMissionRequiresItsOwnManagerUploadEvenWhenPilotsMatch(String origin) {
        f.origin(origin);
        var source = new Mission(); source.setId("original"); source.setOrder(f.order);
        var permit = FlightPermit.pendingFor(source); permit.setStatus(FlightPermitStatus.APPROVED);
        permit.setPilotStaffIds("pilot"); permit.setDataUrl("source-document");
        if (f.mission.getInspectionCorrection() != null) f.mission.getInspectionCorrection().setMission(source);
        f.service.synchronizeForCrew(f.mission);
        assertThat(f.mission.getFlightPermit().getStatus()).isEqualTo(FlightPermitStatus.PENDING);
        assertThat(f.mission.getFlightPermit().getDataUrl()).isNull();
        assertThatThrownBy(() -> f.service.requireApprovedForExecution(f.mission)).isInstanceOf(ApiException.class);
        upload(FlightPermitStatus.APPROVED);
        assertThatCode(() -> f.service.requireApprovedForExecution(f.mission)).doesNotThrowAnyException();
        assertThat(permit.getDataUrl()).isEqualTo("source-document");
    }
    @Test void inheritedDocumentCannotBeUsedForApprovalOrExecutionBeforeReconciliation() {
        inherited();
        assertThatThrownBy(() -> f.service.requireApprovedForExecution(f.mission)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> f.service.updateFlightPermit("mission", new FlightPermitUpdateRequest(FlightPermitStatus.APPROVED, null, null, null, null))).isInstanceOf(ApiException.class);
    }
    @Test void inheritedDocumentIsRetainedButPendingUntilManagerUploadsReplacement() {
        inherited();
        f.service.synchronizeForCrew(f.mission);
        assertThat(f.mission.getFlightPermit().getStatus()).isEqualTo(FlightPermitStatus.PENDING);
        assertThat(f.mission.getFlightPermit().isReplacementRequired()).isTrue();
        assertThat(f.mission.getFlightPermit().getReusedFromMissionId()).isEqualTo("original");
        upload(FlightPermitStatus.PENDING);
        f.service.updateFlightPermit("mission", new FlightPermitUpdateRequest(FlightPermitStatus.APPROVED, "Reviewed", null, null, null));
        assertThat(f.mission.getFlightPermit().getReusedFromMissionId()).isNull();
        assertThatCode(() -> f.service.requireApprovedForExecution(f.mission)).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings = {"IN_PROGRESS", "COMPLETED"})
    void historicalFlightsAreNotRewritten(String status) {
        inherited(); f.mission.setStatus(MissionStatus.valueOf(status));
        f.service.synchronizeForCrew(f.mission);
        assertThat(f.mission.getFlightPermit().getStatus()).isEqualTo(FlightPermitStatus.APPROVED);
        assertThat(f.mission.getFlightPermit().getReusedFromMissionId()).isEqualTo("original");
        verify(f.permits, never()).save(any());
    }
    @Test void pilotChangeStillRequiresReplacementOfManuallyUploadedPermit() {
        upload(FlightPermitStatus.APPROVED);
        f.pilot.getStaff().setId("other");
        assertThatThrownBy(() -> f.service.requireApprovedForExecution(f.mission)).isInstanceOf(ApiException.class);
        f.service.synchronizeForCrew(f.mission);
        assertThat(f.mission.getFlightPermit().isReplacementRequired()).isTrue();
        upload(FlightPermitStatus.APPROVED);
        assertThat(f.mission.getFlightPermit().getPilotStaffIds()).isEqualTo("other");
    }
    @Test void approvedStatusWithoutADocumentDoesNotAuthorizeExecution() {
        var permit = f.mission.getFlightPermit(); permit.setStatus(FlightPermitStatus.APPROVED); permit.setPilotStaffIds("pilot");
        assertThatThrownBy(() -> f.service.requireApprovedForExecution(f.mission)).isInstanceOf(ApiException.class);
    }
}
