package com.ondemandmonitoring.mission.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.ondemandmonitoring.flightpermit.service.IFlightPermitService;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.user.domain.User;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MissionPermitAcceptanceTest {
    @Test void acceptingTheCrewReconcilesThePermitAfterSavingTheirResponse() {
        var f = new MissionServiceTest(); f.setUp();
        var permits = mock(IFlightPermitService.class);
        ReflectionTestUtils.setField(f.missionService, "flightPermitService", permits);
        var order = new Order(); order.setId("o");
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED); order.getExecutionPlan().setRevision(1L);
        var mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        var user = new User(); user.setId("pilot");
        var assignment = MissionStaffAssignment.builder().mission(mission).staff(user).assignedRole(MissionStaffRole.PILOT)
            .isCurrent(true).responseStatus(MissionStaffAssignmentStatus.PENDING).build();
        when(f.missionRepository.findById("m")).thenReturn(Optional.of(mission));
        when(f.missionRepository.save(mission)).thenReturn(mission);
        when(f.missionStaffAssignmentRepository.findByMissionId("m")).thenReturn(List.of(assignment));
        when(f.missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("m", "pilot"))
            .thenReturn(List.of(assignment));
        f.missionService.acceptMission("m", "pilot");
        assertThat(assignment.getResponseStatus()).isEqualTo(MissionStaffAssignmentStatus.ACCEPTED);
        var orderOfCalls = inOrder(f.missionStaffAssignmentRepository, permits);
        orderOfCalls.verify(f.missionStaffAssignmentRepository).saveAll(any());
        orderOfCalls.verify(permits).synchronizeForCrew(mission);
    }
}
