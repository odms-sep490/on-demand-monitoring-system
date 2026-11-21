package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A rework delivery must use the current remediation Mission, never original or earlier-attempt media. */
final class ReworkDeliveryEvidencePolicy {
    private ReworkDeliveryEvidencePolicy() {}

    static boolean isEligibleChecklistEvidence(
            MissionChecklistEvidence link,
            Set<String> delegatedExecutionIds) {
        if (link == null || link.getDetachedAt() != null || link.getMediaAsset() == null) return false;
        var execution = link.getMissionChecklistExecution();
        if (execution == null) return false;
        if (delegatedExecutionIds != null && execution.getId() != null
                && delegatedExecutionIds.contains(execution.getId())) {
            return false;
        }
        return execution.getExecutionStatus() == ChecklistExecutionStatus.COMPLETED
                && execution.getAssessmentStatus() == ChecklistAssessmentStatus.COMPLIANT;
    }

    static boolean allows(ResultRevisionCaseItem item,
            MediaAsset asset,
            List<MissionChecklistEvidence> supplements) {
        if (item == null || item.getStrategy() != ResultRevisionStrategy.REWORK) return true;
        List<MissionChecklistEvidence> safeSupplements = supplements == null ? List.of() : supplements;
        String targetChecklistId = item.getOrderChecklistItem() == null ? null : item.getOrderChecklistItem().getId();
        var replacements = safeSupplements.stream().filter(link ->
                targetChecklistId != null
                    && link.getMissionChecklistExecution() != null
                    && targetChecklistId.equals(
                        link.getMissionChecklistExecution().getOrderChecklistItemId())).toList();
        if (replacements.isEmpty()) return allows(item, asset == null ? null : asset.getMissionId());
        return asset != null && replacements.stream().anyMatch(link ->
                link.getMediaAsset() != null
                    && Objects.equals(link.getMediaAsset().getId(), asset.getId())
                    && Objects.equals(link.getMediaAsset().getMissionId(), asset.getMissionId()));
    }

    static boolean allows(ResultRevisionCaseItem item, String sourceMissionId) {
        return item == null || item.getStrategy() != ResultRevisionStrategy.REWORK
                || (item.getAssignedReworkMissionId() != null
                    && item.getAssignedReworkMissionId().equals(sourceMissionId));
    }
}
