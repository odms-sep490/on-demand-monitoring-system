package com.ondemandmonitoring.feedback.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.feedback.dto.OrderFeedbackResponse;
import com.ondemandmonitoring.feedback.service.OrderFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Manager read access to the customer's rating of an order. */
@RestController
@RequestMapping("/api/manager/orders/{orderId}/feedback")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class ManagerOrderFeedbackController {
    private final OrderFeedbackService feedback;

    @GetMapping
    public ApiResponse<OrderFeedbackResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(feedback.getForManager(orderId));
    }
}
