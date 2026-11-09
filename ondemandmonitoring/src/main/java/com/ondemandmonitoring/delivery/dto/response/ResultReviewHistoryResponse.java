package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus;
import java.time.Instant;
import java.util.List;

public record ResultReviewHistoryResponse(int page, int size, long totalItems, List<Round> rounds) {
    public record Round(String reviewRoundId, int roundNumber, Long roundVersion,
            Instant publishedAt, ResultReviewRoundStatus status, List<ResultReviewResponse.Item> items) {}
}
