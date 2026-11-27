package com.ondemandmonitoring.order.service.impl;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.List;

final class OrderChecklistImageCriteriaSnapshot {
    private OrderChecklistImageCriteriaSnapshot() {}

    static void capture(OrderChecklistItem item, ChecklistDefinition source) {
        var criteria = source == null ? null : source.getImageAssessmentCriteria();
        item.setImageAssessmentCriteriaSnapshot(criteria != null && sameContent(item, source.getContent())
                ? List.copyOf(criteria) : List.of());
    }

    static void carryForward(OrderChecklistItem item, OrderChecklistItem previous) {
        var criteria = previous.getImageAssessmentCriteriaSnapshot();
        item.setImageAssessmentCriteriaSnapshot(criteria != null && sameContent(item, previous.getContent())
                ? List.copyOf(criteria) : List.of());
    }

    private static boolean sameContent(OrderChecklistItem item, String content) {
        return ChecklistContentNormalizer.canonicalize(item.getContent())
                .equalsIgnoreCase(ChecklistContentNormalizer.canonicalize(content));
    }
}
