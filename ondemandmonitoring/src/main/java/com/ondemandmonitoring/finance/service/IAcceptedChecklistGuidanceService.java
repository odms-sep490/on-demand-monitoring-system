package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot;

/** Read boundary for operational consumers; never exposes a manager's mutable draft. */
public interface IAcceptedChecklistGuidanceService {
    /**
     * Load once per order/request and reuse for every checklist item.
     * Returns null if there is no accepted quote or its snapshot is legacy (null).
     * A nonnull snapshot, including an empty one, must not fall back to draft guidance.
     */
    ChecklistGuidanceSnapshot findForOrder(String orderId);
}
