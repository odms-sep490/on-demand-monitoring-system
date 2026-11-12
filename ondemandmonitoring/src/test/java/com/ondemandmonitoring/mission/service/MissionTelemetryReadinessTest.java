package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.impl.MissionService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionTelemetryReadinessTest {
    @Mock MissionRepository missions;
    @Mock MissionDeviceAssignmentRepository devices;
    @Mock MissionStaffAssignmentRepository staffAssignments;
    @Mock DeviceTelemetryRepository telemetry;
    @Mock IMissionChecklistExecutionService checklistExecution;
    @InjectMocks MissionService service;
    AutoCloseable mocks;
    Mission mission;
    MissionDeviceAssignment assignment;
    MissionStaffAssignment crew;
    DeviceTelemetry sample;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        Order order = new Order();
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1);
        mission = new Mission();
        mission.setId("M1");
        mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        mission.setOrder(order);

        Device device = new Device();
        device.setId("device-uuid");
        device.setDeviceCode("DRN-0052");
        assignment = new MissionDeviceAssignment();
        assignment.setId("A1");
        assignment.setMission(mission);
        assignment.setDevice(device);
        assignment.setDeviceRole(DeviceRole.MAIN);
        assignment.setIsCurrent(true);

        crew = new MissionStaffAssignment();
        crew.setId("S1");
        crew.setIsCurrent(true);
        crew.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);

        sample = new DeviceTelemetry();
        sample.setMissionDeviceAssignment(assignment);
        sample.setConnected(true);
        sample.setRecordedAt(Instant.now());

        when(missions.findById("M1")).thenReturn(Optional.of(mission));
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("M1"))
                .thenReturn(List.of(assignment));
        when(staffAssignments.findAllByMissionIdAndIsCurrentTrue("M1"))
                .thenReturn(List.of(crew));
        when(telemetry.findTopByMissionDeviceAssignmentIdOrderByRecordedAtDesc("A1"))
                .thenReturn(Optional.of(sample));
    }

    @AfterEach void close() throws Exception { mocks.close(); }

    @Test void freshTelemetryForCurrentAssignmentReportsDeviceWithoutWrites() throws Exception {
        var response = service.getTelemetryReadiness("M1");
        assertTrue(response.ready());
        assertEquals("device-uuid", response.deviceId());
        assertEquals(sample.getRecordedAt(), response.lastTelemetryAt());
        assertEquals(MissionStatus.PENDING_CONFIRMATION, mission.getStatus());
        assertNull(assignment.getReleasedAt());
        verify(missions, never()).findByIdForUpdate(anyString());
        verify(missions, never()).save(any());
        verify(devices, never()).save(any());
        verify(telemetry, never()).save(any());
        assertTrue(MissionService.class.getMethod("getTelemetryReadiness", String.class)
                .getAnnotation(Transactional.class).readOnly());
    }

    @Test void staleOrDisconnectedTelemetryIsNotReady() {
        sample.setRecordedAt(Instant.now().minusSeconds(11));
        assertFalse(service.getTelemetryReadiness("M1").ready());
        sample.setRecordedAt(Instant.now());
        sample.setConnected(false);
        assertFalse(service.getTelemetryReadiness("M1").ready());
    }

    @Test void missingTelemetryOrUnacceptedCrewIsNotReady() {
        when(telemetry.findTopByMissionDeviceAssignmentIdOrderByRecordedAtDesc("A1"))
                .thenReturn(Optional.empty());
        assertFalse(service.getTelemetryReadiness("M1").ready());
        verify(telemetry).findTopByMissionDeviceAssignmentIdOrderByRecordedAtDesc("A1");

        crew.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(service.getTelemetryReadiness("M1").ready());
        verifyNoMoreInteractions(telemetry);
    }

    @Test void terminalOrUnfinalizedMissionIsNotReady() {
        for (var status : List.of(MissionStatus.CANCELLED, MissionStatus.FAILED,
                MissionStatus.COMPLETED, MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW)) {
            mission.setStatus(status);
            assertFalse(service.getTelemetryReadiness("M1").ready());
        }
        mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        mission.getOrder().getExecutionPlan().setStatus(ExecutionPlanStatus.DRAFT);
        assertFalse(service.getTelemetryReadiness("M1").ready());
        verifyNoInteractions(telemetry);
    }

    @Test void releasedOrNonMainAssignmentUsesNotFoundConvention() {
        assignment.setReleasedAt(Instant.now());
        assertThrows(ApiException.class, () -> service.getTelemetryReadiness("M1"));
        assignment.setReleasedAt(null);
        assignment.setDeviceRole(DeviceRole.SUPPORT);
        assertThrows(ApiException.class, () -> service.getTelemetryReadiness("M1"));
    }

    @Test void orderIdIsNeverResolvedAsMission() {
        assertThrows(ApiException.class, () -> service.getTelemetryReadiness("order-id"));
        verify(missions).findById("order-id");
        verify(missions, never()).findByIdForUpdate(anyString());
    }
}
