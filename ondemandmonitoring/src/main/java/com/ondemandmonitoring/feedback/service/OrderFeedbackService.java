package com.ondemandmonitoring.feedback.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.feedback.domain.OrderFeedback;
import com.ondemandmonitoring.feedback.dto.OrderFeedbackRequest;
import com.ondemandmonitoring.feedback.dto.OrderFeedbackResponse;
import com.ondemandmonitoring.feedback.repository.OrderFeedbackRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer satisfaction feedback: one rating per order, allowed once a delivered result was accepted. */
@Service
@RequiredArgsConstructor
public class OrderFeedbackService {
    /** Ratings at or below this value are surfaced to managers. */
    public static final int LOW_RATING_THRESHOLD = 2;

    private final OrderFeedbackRepository feedbacks;
    private final OrderRepository orders;
    private final OrderDeliveryRepository deliveries;
    private final AuthenticatedUserResolver currentUser;

    @Transactional(readOnly = true)
    public OrderFeedbackResponse getForCustomer(String orderId) {
        ownedOrder(orderId);
        return view(orderId);
    }

    @Transactional(readOnly = true)
    public OrderFeedbackResponse getForManager(String orderId) {
        orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return view(orderId);
    }

    @Transactional
    public OrderFeedbackResponse submit(String orderId, OrderFeedbackRequest request) {
        Order order = ownedOrder(orderId);
        if (!deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull(orderId))
            throw new ApiException(ErrorCode.ORDER_FEEDBACK_NOT_ELIGIBLE);
        if (feedbacks.existsByOrder_Id(orderId))
            throw new ApiException(ErrorCode.ORDER_FEEDBACK_ALREADY_SUBMITTED);
        OrderFeedback feedback = new OrderFeedback();
        feedback.setOrder(order);
        feedback.setCustomer(order.getCustomer());
        feedback.setRating(request.rating());
        feedback.setQualityRating(request.qualityRating());
        feedback.setPunctualityRating(request.punctualityRating());
        feedback.setCommunicationRating(request.communicationRating());
        String comment = request.comment() == null ? null : request.comment().trim();
        feedback.setComment(comment == null || comment.isEmpty() ? null : comment);
        try {
            feedbacks.saveAndFlush(feedback);
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent double submit hits the unique constraint on order_id.
            throw new ApiException(ErrorCode.ORDER_FEEDBACK_ALREADY_SUBMITTED);
        }
        return new OrderFeedbackResponse(orderId, false, OrderFeedbackResponse.Feedback.of(feedback));
    }

    private OrderFeedbackResponse view(String orderId) {
        var existing = feedbacks.findByOrder_Id(orderId);
        if (existing.isPresent())
            return new OrderFeedbackResponse(orderId, false, OrderFeedbackResponse.Feedback.of(existing.get()));
        return new OrderFeedbackResponse(orderId, deliveries.existsByOrder_IdAndCustomerAcceptedAtIsNotNull(orderId), null);
    }

    private Order ownedOrder(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!order.getCustomer().getId().equals(currentUser.getCurrentUserId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        return order;
    }
}
