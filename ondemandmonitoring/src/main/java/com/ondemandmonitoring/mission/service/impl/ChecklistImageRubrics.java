package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.List;
import java.util.stream.IntStream;

final class ChecklistImageRubrics {
    private ChecklistImageRubrics() {}

    static List<CriterionInput> forChecklist(OrderChecklistItem item) {
        if (!enabled(item)) return List.of();
        var criteria = item.getImageAssessmentCriteriaSnapshot();
        if (criteria == null) return List.of();
        return IntStream.range(0, criteria.size())
                .mapToObj(index -> {
                    var instruction = criteria.get(index);
                    return new CriterionInput("PHOTO_" + (index + 1), instruction, instruction);
                }).toList();
    }

    static boolean enabled(OrderChecklistItem item) {
        var source = item.getSourceChecklist();
        return source == null || source.isImageAssessmentEnabled();
    }
}
