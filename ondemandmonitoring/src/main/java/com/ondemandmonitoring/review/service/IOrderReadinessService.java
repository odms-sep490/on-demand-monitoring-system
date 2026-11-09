package com.ondemandmonitoring.review.service;

import com.ondemandmonitoring.review.dto.request.ManualCriterionRequest;
import com.ondemandmonitoring.review.dto.response.OrderReadinessResponse;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;

public interface IOrderReadinessService {

    /** Computes the automatic criteria and merges the manager's manual answers. */
    OrderReadinessResponse evaluate(String orderId);

    /** Records the manager's answer for a MANUAL criterion and returns the new state. */
    OrderReadinessResponse answer(String orderId, ReviewCriterionCode code, ManualCriterionRequest request);

    /**
     * Gate for approving an order's quote. Throws when a blocking criterion fails or a
     * required manual criterion is unanswered; otherwise stores the automatic verdicts
     * as evidence of what the system saw at approval time.
     */
    void requireApprovable(String orderId);
}
