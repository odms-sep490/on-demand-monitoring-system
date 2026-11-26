package com.ondemandmonitoring.feedback.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.feedback.dto.OrderFeedbackRequest;
import com.ondemandmonitoring.feedback.dto.OrderFeedbackResponse;
import com.ondemandmonitoring.feedback.service.OrderFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Customer endpoints for rating an order after accepting its result. */
@RestController
@RequestMapping("/api/orders/{orderId}/feedback")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerOrderFeedbackController {
    private final OrderFeedbackService feedback;

    @GetMapping
    public ApiResponse<OrderFeedbackResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(feedback.getForCustomer(orderId));
    }

    @PostMapping
    public ApiResponse<OrderFeedbackResponse> submit(@PathVariable String orderId, @Valid @RequestBody OrderFeedbackRequest request) {
        return ApiResponse.ok("Feedback submitted", feedback.submit(orderId, request));
    }
}
