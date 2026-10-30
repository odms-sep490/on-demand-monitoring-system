package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import java.util.Objects;

/** Pure guidance copy/reset rules, applied after the candidate scope has been populated. */
public final class OrderChecklistGuidanceSupport {
    private OrderChecklistGuidanceSupport() {}

    public static void copyForCustomerRevision(OrderChecklistItem existing, OrderChecklistItem candidate) {
        if (existing.getSourceType() != OrderChecklistSourceType.CUSTOMER_CUSTOM) return;
        // The value object is immutable. Never mutate the historical item or re-confirm an old version.
        candidate.setCustomGuidance(existing.getCustomGuidance());
        candidate.setGuidanceVersion(existing.getGuidanceVersion());
        candidate.setGuidanceUpdatedBy(existing.getGuidanceUpdatedBy());
        candidate.setGuidanceUpdatedAt(existing.getGuidanceUpdatedAt());
        candidate.setGuidanceConfirmedVersion(scopeChanged(existing, candidate)
                ? null : existing.getGuidanceConfirmedVersion());
    }

    public static boolean scopeChanged(OrderChecklistItem existing, OrderChecklistItem candidate) {
        return !Objects.equals(existing.getContent(), candidate.getContent())
                || existing.getEvidencePolicyVersion() != candidate.getEvidencePolicyVersion()
                || existing.getRequestedImageCount() != candidate.getRequestedImageCount()
                || existing.getRequestedVideoCount() != candidate.getRequestedVideoCount()
                || existing.getMinimumImageCount() != candidate.getMinimumImageCount()
                || existing.getMinimumVideoCount() != candidate.getMinimumVideoCount()
                || existing.getMinimumEvidenceCount() != candidate.getMinimumEvidenceCount()
                || existing.getRequiredEvidenceType() != candidate.getRequiredEvidenceType();
    }
}
