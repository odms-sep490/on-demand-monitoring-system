package com.ondemandmonitoring.delivery.dto.response;

import java.time.Instant;
import java.util.List;

public record ResultRevisionTicketReviewResponse(String ticketId, String ticketCode, String orderId,
        String reviewRoundId, int roundNumber, Long roundVersion, Instant publishedAt, String generalNote,
        Instant submittedAt, List<Item> items) {
    public record Item(String roundItemId, String orderChecklistItemId, String title, String description,
            List<String> executionSteps, List<String> inputRequirements, List<String> expectedOutputs,
            String missionId, String missionCode, String reasonCode, String note, List<String> roundEvidenceIds,
            List<ResultReviewResponse.Evidence> evidence) {}
}
