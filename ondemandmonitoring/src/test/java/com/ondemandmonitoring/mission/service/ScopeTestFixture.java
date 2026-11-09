package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.*;
import static org.mockito.Mockito.*;

/** Only role/lifecycle unit tests use this stub; domain/PostgreSQL tests use the real checker. */
public final class ScopeTestFixture {
    private ScopeTestFixture() {}
    public static IMissionChecklistScopeService validScope() {
        var scope = mock(IMissionChecklistScopeService.class);
        lenient().when(scope.isMaterialized(any())).thenReturn(true);
        return scope;
    }
    public static MissionChecklistAssignment link(MissionChecklistExecution execution, Mission mission) {
        var assignment = new MissionChecklistAssignment();
        assignment.setId("assignment-" + execution.getId());
        assignment.setMissionId(mission.getId()); assignment.setOrderId(mission.getOrder().getId());
        assignment.setOrderChecklistItemId(execution.getOrderChecklistItemId());
        assignment.setOrderChecklistItem(execution.getOrderChecklistItem()); assignment.setMission(mission);
        assignment.setAssignedAt(java.time.Instant.now());
        execution.setMissionChecklistAssignment(assignment); execution.setMissionChecklistAssignmentId(assignment.getId());
        return assignment;
    }
}
