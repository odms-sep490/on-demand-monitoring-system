package com.ondemandmonitoring.mission.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheck;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MissionCompletionInspectionGateTest {
    @Test void unassessedOrUnselectedEvidenceCannotCompleteOrReleaseMission() {
        var fixture = new MissionServiceTest();
        fixture.setUp();
        var order = new Order(); order.setId("o");
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1L);
        var mission = new Mission(); mission.setId("m"); mission.setOrder(order);
        mission.setStatus(MissionStatus.IN_PROGRESS); mission.setActualEndAt(Instant.now());
        when(fixture.missionRepository.findById("m")).thenReturn(Optional.of(mission));
        when(fixture.orderRepository.findById("o")).thenReturn(Optional.of(order));
        when(fixture.postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc("m"))
            .thenReturn(Optional.of(new PersistedPostDeviceCheck()));
        var checklist = mock(IMissionChecklistExecutionService.class);
        ReflectionTestUtils.setField(fixture.missionService, "checklistExecutionService", checklist);
        doThrow(new ApiException(ErrorCode.CHECKLIST_NOT_READY))
            .when(checklist).requireReadyForInspectorApproval(mission);
        assertThatThrownBy(() -> fixture.missionService.completeMission("m"))
            .isInstanceOf(ApiException.class);
        assertThat(mission.getCompletedAt()).isNull();
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        verify(fixture.missionRepository, never()).save(any());
        verify(fixture.missionDeviceAssignmentRepository, never()).saveAll(any());
        verify(fixture.missionStaffAssignmentRepository, never()).saveAll(any());
        verify(fixture.missionResultService, never()).ensureCompletedResult(any());
    }
}
