package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus;
import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus;
import com.ondemandmonitoring.delivery.enums.ResultRevisionScope;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import com.ondemandmonitoring.delivery.enums.ResultRevisionReasonCode;
import java.time.Instant;
import java.util.List;

public record ResultRevisionCaseResponse(
        String caseId,
        String orderId,
        String ticketId,
        String ticketCode,
        ResultRevisionCaseStatus status,
        boolean active,
        Long version,
        Instant openedAt,
        List<Item> items) {

    public record Item(
            String caseItemId,
            String orderChecklistItemId,
            String title,
            ResultRevisionScope scopeDecision,
            ResultRevisionStrategy strategy,
            ResultRevisionCaseItemStatus status,
            String scopeReason,
            String managerInstructions,
            String assignedReworkMissionId,
            String assignedReworkMissionStatus,
            Long version,
            List<Feedback> feedback) {}

    public record Feedback(String requestId, int roundNumber, ResultRevisionReasonCode reasonCode,
            String customerNote, Instant submittedAt, List<ResultReviewResponse.Evidence> evidence) {}
}
