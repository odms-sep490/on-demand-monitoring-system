package com.ondemandmonitoring.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderChecklistImageCriteriaSnapshotTest {
    @Test
    void capturesAnIndependentCopyAndCarriesItAcrossOrderRevisions() {
        var source = new ChecklistDefinition();
        source.setContent("Photograph the roof");
        var criteria = new ArrayList<>(List.of("Entire roof is visible"));
        source.setImageAssessmentCriteria(criteria);
        var item = item(source.getContent());
        OrderChecklistImageCriteriaSnapshot.capture(item, source);
        criteria.clear();

        var revision = item(item.getContent());
        OrderChecklistImageCriteriaSnapshot.carryForward(revision, item);
        assertEquals(List.of("Entire roof is visible"), revision.getImageAssessmentCriteriaSnapshot());
    }

    @Test
    void keepsUnconfiguredAndChangedChecklistsWithoutImageCriteria() {
        var unconfigured = item("Photograph the roof");
        var revision = item(unconfigured.getContent());
        OrderChecklistImageCriteriaSnapshot.carryForward(revision, unconfigured);
        assertEquals(List.of(), revision.getImageAssessmentCriteriaSnapshot());

        var changed = item("Photograph the road");
        OrderChecklistImageCriteriaSnapshot.carryForward(changed, unconfigured);
        assertEquals(List.of(), changed.getImageAssessmentCriteriaSnapshot());

        var custom = item("Customer checklist");
        OrderChecklistImageCriteriaSnapshot.capture(custom, null);
        assertEquals(List.of(), custom.getImageAssessmentCriteriaSnapshot());
    }

    private static OrderChecklistItem item(String content) {
        var item = new OrderChecklistItem();
        item.setContent(content);
        return item;
    }
}
