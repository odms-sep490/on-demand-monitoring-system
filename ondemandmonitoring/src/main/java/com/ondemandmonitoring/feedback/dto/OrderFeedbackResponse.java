package com.ondemandmonitoring.feedback.dto;

import com.ondemandmonitoring.feedback.domain.OrderFeedback;
import java.time.Instant;

/**
 * Feedback state of an order. {@code eligible} tells the customer UI whether the rating form may be shown;
 * {@code feedback} is null until submitted.
 */
public record OrderFeedbackResponse(String orderId, boolean eligible, Feedback feedback) {
    public record Feedback(String id, int rating, Integer qualityRating, Integer punctualityRating,
            Integer communicationRating, String comment, Instant createdAt) {
        public static Feedback of(OrderFeedback f) {
            return new Feedback(f.getId(), f.getRating(), f.getQualityRating(), f.getPunctualityRating(),
                    f.getCommunicationRating(), f.getComment(), f.getCreatedAt());
        }
    }
}
