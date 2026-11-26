package com.ondemandmonitoring.resourcelifecycle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import com.ondemandmonitoring.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MissionUsageRecorderTest {
    @Mock ResourceUsageRecordRepository usageRepository;
    @Mock MissionDeviceAssignmentRepository deviceAssignments;
    @Mock MissionStaffAssignmentRepository staffAssignments;
    @Mock DeviceTelemetryRepository telemetryRepository;
    @Mock MaintenanceLifecycleService maintenanceLifecycleService;
    @Mock ResourceAuditService auditService;
    @InjectMocks MissionUsageRecorder recorder;

    private final Instant start = Instant.parse("2026-11-25T01:00:00Z");
    private final Instant end = Instant.parse("2026-11-25T02:30:00Z");
    private Mission mission;

    @BeforeEach
    void setUp() {
        mission = new Mission();
        mission.setId("mission-1");
        mission.setActualStartAt(start);
        mission.setActualEndAt(end);

        Device drone = new Device();
        drone.setId("drone-1");
        MissionDeviceAssignment deviceAssignment = new MissionDeviceAssignment();
        deviceAssignment.setId("da-1");
        deviceAssignment.setDevice(drone);

        User accepted = new User();
        accepted.setId("staff-1");
        MissionStaffAssignment acceptedAssignment = new MissionStaffAssignment();
        acceptedAssignment.setId("sa-1");
        acceptedAssignment.setStaff(accepted);
        acceptedAssignment.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);

        User rejected = new User();
        rejected.setId("staff-2");
        MissionStaffAssignment rejectedAssignment = new MissionStaffAssignment();
        rejectedAssignment.setStaff(rejected);
        rejectedAssignment.setResponseStatus(MissionStaffAssignmentStatus.REJECTED);

        when(deviceAssignments.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("mission-1")).thenReturn(List.of(deviceAssignment));
        when(staffAssignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(List.of(acceptedAssignment, rejectedAssignment));
        when(telemetryRepository.findLatestForMissionDevice(eq("mission-1"), eq("drone-1"), any())).thenReturn(List.of());
        when(usageRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void recordsVerifiedMissionHoursForDroneAndAcceptedStaff() {
        recorder.recordFinishedMission(mission);

        ArgumentCaptor<ResourceUsageRecordEntity> saved = ArgumentCaptor.forClass(ResourceUsageRecordEntity.class);
        verify(usageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ResourceUsageRecordEntity::getResourceKind, ResourceUsageRecordEntity::getResourceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DEVICE", "drone-1"), org.assertj.core.groups.Tuple.tuple("STAFF", "staff-1"));
        assertThat(saved.getAllValues()).allSatisfy(record -> {
            assertThat(record.getStatus()).isEqualTo("VERIFIED");
            assertThat(record.getActualSeconds()).isEqualTo(5400L);
            assertThat(record.getSource()).isEqualTo(MissionUsageRecorder.SOURCE_MISSION);
        });
        // No transaction is active in a unit test, so maintenance is evaluated immediately.
        verify(maintenanceLifecycleService).evaluateAndTriggerIfBreached("drone-1");
    }

    @Test
    void isIdempotentPerMissionAndResource() {
        when(usageRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(new ResourceUsageRecordEntity()));

        recorder.recordFinishedMission(mission);

        verify(usageRepository, never()).save(any());
        verify(maintenanceLifecycleService, never()).evaluateAndTriggerIfBreached(anyString());
    }

    @Test
    void skipsMissionWithoutActualTimes() {
        mission.setActualStartAt(null);

        recorder.recordFinishedMission(mission);

        verify(usageRepository, never()).save(any());
    }
}
