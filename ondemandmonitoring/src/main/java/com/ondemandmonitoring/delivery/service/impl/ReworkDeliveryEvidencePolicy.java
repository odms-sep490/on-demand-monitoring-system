package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import java.util.List;

/** A rework delivery must use the current remediation Mission, never original or earlier-attempt media. */
final class ReworkDeliveryEvidencePolicy {
    private ReworkDeliveryEvidencePolicy() {}

    static boolean allows(ResultRevisionCaseItem item,
            MediaAsset asset,
            List<MissionChecklistEvidence> supplements) {
        if (item == null || item.getStrategy() != ResultRevisionStrategy.REWORK) return true;
        var replacements = supplements.stream().filter(link ->
                item.getOrderChecklistItem().getId().equals(
                    link.getMissionChecklistExecution().getOrderChecklistItemId())).toList();
        if (replacements.isEmpty()) return allows(item, asset.getMissionId());
        return replacements.stream().anyMatch(link ->
                link.getMediaAsset().getId().equals(asset.getId())
                    && link.getMediaAsset().getMissionId().equals(asset.getMissionId()));
    }

    static boolean allows(ResultRevisionCaseItem item, String sourceMissionId) {
        return item == null || item.getStrategy() != ResultRevisionStrategy.REWORK
                || (item.getAssignedReworkMissionId() != null
                    && item.getAssignedReworkMissionId().equals(sourceMissionId));
    }
}
