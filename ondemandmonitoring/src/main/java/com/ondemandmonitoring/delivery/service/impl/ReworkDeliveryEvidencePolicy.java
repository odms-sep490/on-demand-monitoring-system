package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;

/** A rework delivery must use the current remediation Mission, never original or earlier-attempt media. */
final class ReworkDeliveryEvidencePolicy {
    private ReworkDeliveryEvidencePolicy() {}

    static boolean allows(ResultRevisionCaseItem item, String sourceMissionId) {
        return item == null || item.getStrategy() != ResultRevisionStrategy.REWORK
                || (item.getAssignedReworkMissionId() != null
                    && item.getAssignedReworkMissionId().equals(sourceMissionId));
    }
}
