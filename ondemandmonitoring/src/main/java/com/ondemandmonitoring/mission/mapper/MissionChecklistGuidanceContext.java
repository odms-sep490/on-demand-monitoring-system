package com.ondemandmonitoring.mission.mapper;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import java.util.HashMap;
import java.util.Map;

/** Accepted guidance indexed once for a request/batch; contains no persistence reads. */
public final class MissionChecklistGuidanceContext {
    private final boolean legacy;
    private final Map<String, ChecklistGuidanceSnapshot.Item> items = new HashMap<>();

    public MissionChecklistGuidanceContext(ChecklistGuidanceSnapshot snapshot) {
        legacy = snapshot == null;
        if (snapshot != null && snapshot.items() != null) {
            for (var item : snapshot.items()) {
                if (item != null && items.putIfAbsent(item.itemId(), item) != null) {
                    throw invalid(item.itemId());
                }
            }
        }
    }

    public ChecklistGuidanceSnapshot.Item resolve(OrderChecklistItem item) {
        if (item == null || item.getSourceType() != OrderChecklistSourceType.CUSTOMER_CUSTOM || legacy) return null;
        var guidance = items.get(item.getId());
        if (guidance == null || guidance.checklistRevision() != item.getChecklistRevision()) {
            throw invalid(item.getId());
        }
        return guidance;
    }

    private static ApiException invalid(String itemId) {
        return new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID,
                "Accepted quote guidance is missing or inconsistent for checklist item " + itemId);
    }
}
