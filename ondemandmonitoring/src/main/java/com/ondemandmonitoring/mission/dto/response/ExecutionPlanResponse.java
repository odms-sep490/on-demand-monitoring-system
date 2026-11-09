package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import java.util.List;

public record ExecutionPlanResponse(String orderId, ExecutionPlanStatus planStatus, long revision,
        List<MissionScope> missions, Coverage coverage, List<String> blockers) {
    public record MissionScope(String missionId, String scopeLabel, int assignmentCount, List<String> checklistItemIds) {}
    public record Coverage(int total, int covered, int uncovered, int repeated,
            boolean coverageReady, List<String> uncoveredItemIds) {}
}
