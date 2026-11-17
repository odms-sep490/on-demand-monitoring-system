package com.ondemandmonitoring.mission.mapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;

class MissionMapperHelperDeviceHistoryTest {
    MissionDeviceAssignmentRepository devices = mock(MissionDeviceAssignmentRepository.class);
    MissionMapperHelper helper = new MissionMapperHelper(mock(MissionPlanRepository.class), devices,
        mock(MissionStaffAssignmentRepository.class), mock(MissionPlanMapper.class),
        mock(MissionStaffAssignmentMapper.class), Mappers.getMapper(MissionDeviceAssignmentMapper.class),
        mock(UserRepository.class));
    Mission mission = new Mission();
    @BeforeEach void setup() { mission.setId("m"); mission.setStatus(MissionStatus.IN_PROGRESS); }
    MissionDeviceAssignment row(String id, DeviceRole role, String reason) {
        var device = new Device(); device.setId(id); device.setDeviceCode("DRN-" + id);
        var assignment = MissionDeviceAssignment.builder().mission(mission).device(device).deviceRole(role)
            .isCurrent(reason == null).releasedAt(reason == null ? null : Instant.now())
            .releaseReason(reason).build();
        assignment.setId("assignment-" + id); return assignment;
    }
    @Test void returnsTheEntireReleasedFleetWhileBusinessInspectionIsPending() {
        mission.setCompletedAt(Instant.now());
        when(devices.findByMissionId("m")).thenReturn(List.of(row("support", DeviceRole.SUPPORT, "MISSION_COMPLETE"), row("main", DeviceRole.MAIN, "MISSION_COMPLETE")));
        assertThat(helper.getDeviceAssignments(mission)).extracting(r -> r.getDeviceId()).containsExactly("main", "support");
    }
    @Test void doesNotRestoreAnOldReplacedDroneAlongsideTheCompletedFleet() {
        mission.setCompletedAt(Instant.now());
        when(devices.findByMissionId("m")).thenReturn(List.of(row("old", DeviceRole.MAIN, "REPLACED"), row("main", DeviceRole.MAIN, "MISSION_COMPLETE")));
        assertThat(helper.getDeviceAssignments(mission)).extracting(r -> r.getDeviceId()).containsExactly("main");
    }
    @Test void activeMissionWithoutPostflightCompletionDoesNotGainHistoricalAssignments() {
        when(devices.findByMissionId("m")).thenReturn(List.of(row("released", DeviceRole.MAIN, "MISSION_COMPLETE"), row("current", DeviceRole.SUPPORT, null)));
        assertThat(helper.getDeviceAssignments(mission)).extracting(r -> r.getDeviceId()).containsExactly("current");
    }
    @Test void preservesExistingCompletedAndFailedMissionHistory() {
        when(devices.findByMissionId("m")).thenReturn(List.of(row("complete", DeviceRole.MAIN, "MISSION_COMPLETE"), row("failed", DeviceRole.MAIN, "MISSION_FAILED")));
        mission.setStatus(MissionStatus.COMPLETED);
        assertThat(helper.getDeviceAssignments(mission)).extracting(r -> r.getDeviceId()).containsExactly("complete");
        mission.setStatus(MissionStatus.FAILED);
        assertThat(helper.getDeviceAssignments(mission)).extracting(r -> r.getDeviceId()).containsExactly("failed");
    }
}
