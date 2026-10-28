package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionTelemetryReadinessTest {
    @Mock MissionRepository missions;
    @Mock MissionDeviceAssignmentRepository devices;
    @Mock DeviceConnectionRepository connections;
    @Mock DeviceTelemetryRepository telemetry;
    @Mock IMissionChecklistExecutionService scope;
    @InjectMocks MissionService service;
    AutoCloseable mocks;
    Mission mission;
    MissionDeviceAssignment assignment;
    DeviceConnection session;
    DeviceTelemetry sample;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        mission = new Mission(); mission.setId("M1"); mission.setStatus(MissionStatus.CONNECTED);
        Device device = new Device(); device.setId("device-uuid"); device.setDeviceCode("DRN-0052");
        assignment = new MissionDeviceAssignment(); assignment.setId("A1"); assignment.setMission(mission); assignment.setDevice(device);
        MissionStaffAssignment crew = new MissionStaffAssignment(); crew.setId("S1"); crew.setIsCurrent(true); crew.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);
        session = new DeviceConnection(); session.setId("C1"); session.setMission(mission); session.setDeviceAssignment(assignment);
        session.setStaffAssignment(crew); session.setConnectionStatus("CONNECTED");
        sample = new DeviceTelemetry(); sample.setDeviceConnection(session); sample.setConnected(true);
        when(missions.findById("M1")).thenReturn(Optional.of(mission));
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("M1")).thenReturn(List.of(assignment));
        when(connections.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc("M1", "CONNECTED")).thenReturn(Optional.of(session));
        when(telemetry.findTopByDeviceConnectionIdOrderByRecordedAtDesc("C1")).thenReturn(Optional.of(sample));
    }
    @AfterEach void close() throws Exception { mocks.close(); }

    @Test void freshExactSessionReportsUuidWithoutRuntimeLockOrWrites() throws Exception {
        var response = service.getTelemetryReadiness("M1");
        assertTrue(response.ready()); assertEquals("device-uuid",response.deviceId()); assertEquals(sample.getRecordedAt(),response.lastTelemetryAt());
        assertEquals(MissionStatus.CONNECTED,mission.getStatus()); assertNull(assignment.getReleasedAt());
        verify(missions,never()).findByIdForUpdate(anyString());
        verify(missions,never()).save(any()); verify(connections,never()).save(any()); verify(devices,never()).save(any()); verify(telemetry,never()).save(any());
        assertTrue(MissionService.class.getMethod("getTelemetryReadiness",String.class).getAnnotation(Transactional.class).readOnly());
    }
    @Test void staleOrDisconnectedTelemetryIsNotReady() {
        sample.setRecordedAt(Instant.now().minusSeconds(11)); assertFalse(service.getTelemetryReadiness("M1").ready());
        sample.setRecordedAt(Instant.now()); sample.setConnected(false); assertFalse(service.getTelemetryReadiness("M1").ready());
    }
    @Test void noTelemetryIsNotReady() {
        when(telemetry.findTopByDeviceConnectionIdOrderByRecordedAtDesc("C1")).thenReturn(Optional.empty());
        assertFalse(service.getTelemetryReadiness("M1").ready());
    }
    @Test void closedOrInactiveSessionIsNotReady() {
        session.setTelemetryActive(false); assertFalse(service.getTelemetryReadiness("M1").ready());
        session.setTelemetryActive(true); session.setDisconnectedAt(Instant.now()); assertFalse(service.getTelemetryReadiness("M1").ready());
    }
    @Test void noSessionIsNotReady() {
        when(connections.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc("M1","CONNECTED")).thenReturn(Optional.empty());
        assertFalse(service.getTelemetryReadiness("M1").ready());
    }
    @Test void historicalAssignmentSessionCannotMakeCurrentAssignmentReady() {
        var historical = new MissionDeviceAssignment(); historical.setId("old-A"); historical.setDevice(assignment.getDevice());
        session.setDeviceAssignment(historical); assertFalse(service.getTelemetryReadiness("M1").ready());
        verifyNoInteractions(telemetry);
    }
    @Test void releasedOrUnacceptedSessionCrewIsNotReady() {
        session.getStaffAssignment().setReleasedAt(Instant.now()); assertFalse(service.getTelemetryReadiness("M1").ready());
        session.getStaffAssignment().setReleasedAt(null); session.getStaffAssignment().setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(service.getTelemetryReadiness("M1").ready());
    }
    @Test void cancelledFailedReviewCompletedAndSafeStopAreNotReady() {
        for (var status : List.of(MissionStatus.CANCELLED,MissionStatus.FAILED,MissionStatus.COMPLETED,MissionStatus.PENDING_REVIEW)) {
            mission.setStatus(status); assertFalse(service.getTelemetryReadiness("M1").ready());
        }
        mission.setStatus(MissionStatus.CONNECTED); mission.setSafeStopRequestedAt(Instant.now());
        assertFalse(service.getTelemetryReadiness("M1").ready()); verifyNoInteractions(telemetry);
    }
    @Test void nonFinalizedMissionIsNotReady() {
        assertFalse(service.getTelemetryReadiness("M1").ready());
    }
    @Test void releasedOrMissingMainDeviceUsesExistingNotFoundConvention() {
        assignment.setReleasedAt(Instant.now()); assertThrows(ApiException.class,() -> service.getTelemetryReadiness("M1"));
        assignment.setReleasedAt(null); assignment.setDeviceRole(DeviceRole.SUPPORT);
        assertThrows(ApiException.class,() -> service.getTelemetryReadiness("M1"));
    }
    @Test void orderIdIsNeverResolvedAsMission() {
        assertThrows(ApiException.class,() -> service.getTelemetryReadiness("order-id")); verify(missions).findById("order-id");
        verify(missions,never()).findByIdForUpdate(anyString());
    }
    @Test void siblingUsesOnlyOwnSessionAndTelemetry() {
        Mission sibling = new Mission(); sibling.setId("M2"); sibling.setStatus(MissionStatus.CONNECTED);
        when(missions.findById("M2")).thenReturn(Optional.of(sibling));
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("M2")).thenReturn(List.of());
        assertThrows(ApiException.class,() -> service.getTelemetryReadiness("M2")); verifyNoInteractions(telemetry);
    }
}
