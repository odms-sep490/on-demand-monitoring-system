package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.response.ExecutionPlanResponse.Coverage;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.*;
import org.springframework.stereotype.Service;

/** Coverage is a planning rule, independent of terminal execution/evidence readiness. */
@Service
public class ExecutionPlanCoverageService {
    public static boolean effective(Mission mission) {
        return mission.getStatus() != MissionStatus.CANCELLED && mission.getCancellationAt() == null;
    }

    public Coverage evaluate(List<OrderChecklistItem> items, List<Mission> missions,
            List<MissionChecklistAssignment> assignments) {
        var byMission = new HashMap<String, Mission>();
        missions.forEach(m -> byMission.put(m.getId(), m));
        var counts = new LinkedHashMap<String, Integer>();
        items.forEach(item -> counts.put(item.getId(), 0));
        var pairs = new HashSet<List<String>>();
        for (var assignment : assignments) {
            if (assignment.getRemovedAt() != null) continue;
            var mission = byMission.get(assignment.getMissionId());
            if (!ChecklistAssignmentIntegrity.matches(mission, assignment)
                    || !counts.containsKey(assignment.getOrderChecklistItemId()))
                throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
            if (!pairs.add(List.of(mission.getId(), assignment.getOrderChecklistItemId())))
                throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_DUPLICATE);
            // A rework mission is an audited execution attempt for an already-covered
            // Order item. It must have its own assignment/execution but cannot count
            // as a second slot in the original finalized plan.
            if (mission.getPurpose() == com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK) continue;
            if (effective(mission)) counts.compute(assignment.getOrderChecklistItemId(), (id, count) -> count + 1);
        }
        var missing = counts.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();
        int repeated = (int) counts.values().stream().filter(count -> count > 1).count();
        return new Coverage(items.size(), items.size() - missing.size(), missing.size(), repeated, missing.isEmpty(), missing);
    }
}
