package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import com.ondemandmonitoring.checklist.config.ChecklistDefaultImageCriteria;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.List;
import java.util.stream.IntStream;

final class ChecklistImageRubrics {
    private ChecklistImageRubrics() {}

    static List<CriterionInput> forChecklist(OrderChecklistItem item, String language) {
        var criteria = item.getImageAssessmentCriteriaSnapshot();
        if (criteria != null) {
            return IntStream.range(0, criteria.size())
                    .mapToObj(index -> {
                        var instruction = criteria.get(index);
                        return new CriterionInput("PHOTO_" + (index + 1), instruction, instruction);
                    }).toList();
        }

        var source = item.getSourceChecklist();
        return source == null ? List.of()
                : ChecklistDefaultImageCriteria.forSeed(source.getSeedCode(), item.getContent()).stream()
                        .map(criterion -> new CriterionInput(criterion.code(),
                                "vi".equals(language) ? criterion.vietnamese() : criterion.english(),
                                criterion.instruction()))
                        .toList();
    }
}
