package com.ondemandmonitoring.notification.service;

import com.ondemandmonitoring.feedback.repository.OrderFeedbackRepository;
import com.ondemandmonitoring.feedback.service.OrderFeedbackService;
import com.ondemandmonitoring.notification.dto.NotificationItemResponse;
import com.ondemandmonitoring.notification.dto.NotificationType;
import com.ondemandmonitoring.order.domain.Order;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Feedback invitations for customers and low-rating alerts for managers, derived from stored state. */
@Service
@RequiredArgsConstructor
public class FeedbackNotificationService {
    private final OrderFeedbackRepository feedbacks;

    public List<NotificationItemResponse> customerItems(String customerId, int limit) {
        var byOrder = new LinkedHashMap<String, NotificationItemResponse>();
        feedbacks.findAcceptedWithoutFeedback(customerId, PageRequest.of(0, limit * 2)).forEach(d -> {
            Order order = d.getOrder();
            byOrder.putIfAbsent(order.getId(), new NotificationItemResponse("feedback-request:" + order.getId(),
                    NotificationType.ORDER_FEEDBACK_REQUESTED, order.getId(), null, reference(order), d.getCustomerAcceptedAt()));
        });
        return byOrder.values().stream().limit(limit).toList();
    }

    public List<NotificationItemResponse> managerItems(int limit) {
        return feedbacks.findByRatingLessThanEqualOrderByCreatedAtDesc(OrderFeedbackService.LOW_RATING_THRESHOLD, PageRequest.of(0, limit))
                .stream()
                .map(f -> new NotificationItemResponse("feedback-low:" + f.getId(), NotificationType.ORDER_FEEDBACK_LOW_RATING,
                        f.getOrder().getId(), f.getId(), reference(f.getOrder()), f.getCreatedAt()))
                .toList();
    }

    private static String reference(Order order) {
        return order.getOrderCode() == null || order.getOrderCode().isBlank() ? order.getId() : order.getOrderCode();
    }
}
