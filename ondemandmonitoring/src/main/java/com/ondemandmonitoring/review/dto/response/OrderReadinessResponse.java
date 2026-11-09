package com.ondemandmonitoring.review.dto.response;

import com.ondemandmonitoring.review.enums.CriterionResult;
import com.ondemandmonitoring.review.enums.ReadinessRecommendation;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.enums.ReviewCriterionType;
import java.time.Instant;
import java.util.List;

public record OrderReadinessResponse(
        String orderId,
        List<Criterion> criteria,
        boolean canApprove,
        List<String> blockingReasons,
        ReadinessRecommendation recommendation) {

    public record Criterion(
            ReviewCriterionCode code,
            ReviewCriterionType type,
            String title,
            String description,
            boolean required,
            CriterionResult result,
            /** System explanation (AUTO criteria). */
            String reason,
            /** Manager remark (MANUAL criteria). */
            String note,
            String decidedByName,
            Instant decidedAt) {}
}
