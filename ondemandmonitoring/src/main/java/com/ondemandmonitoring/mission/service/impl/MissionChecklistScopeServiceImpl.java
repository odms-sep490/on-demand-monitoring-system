package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionChecklistScopeService;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionChecklistScopeServiceImpl implements IMissionChecklistScopeService {
    private final MissionChecklistAssignmentRepository assignments;
    private final MissionChecklistExecutionRepository executions;

    @Override
    public boolean isMaterialized(Mission mission) {
        if (!MissionRuntimeGate.isStructurallyFinalized(mission)) return false;
        var expected = assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(mission.getId());
        var actual = executions.findOrderedByMissionId(mission.getId());
        var ids = new HashSet<String>();
        var itemIds = new HashSet<String>();
        for (var assignment : expected) {
            if (!ChecklistAssignmentIntegrity.matches(mission, assignment)
                    || !ids.add(assignment.getId()) || !itemIds.add(assignment.getOrderChecklistItemId())) return false;
        }
        if (actual.size() != expected.size()) return false;
        for (var execution : actual) {
            if (!ChecklistAssignmentIntegrity.matches(mission, execution)
                    || !ids.remove(execution.getMissionChecklistAssignmentId())) return false;
        }
        return ids.isEmpty();
    }

    @Override
    public void requireMaterialized(Mission mission) {
        MissionRuntimeGate.requireFinalizedStructure(mission);
        if (!isMaterialized(mission)) throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
    }
}
