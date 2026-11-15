package com.ondemandmonitoring.review.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.review.dto.request.ManualCriterionRequest;
import com.ondemandmonitoring.review.dto.response.OrderReadinessResponse;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.service.IOrderReadinessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/orders/{orderId}/readiness")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class ManagerReadinessController {
    private final IOrderReadinessService readiness;
    private final com.ondemandmonitoring.review.service.impl.OrderReadinessService customReadiness;

    @GetMapping
    public ApiResponse<OrderReadinessResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(readiness.evaluate(orderId));
    }

    @PutMapping("/{code}")
    public ApiResponse<OrderReadinessResponse> answer(@PathVariable String orderId,
                                                       @PathVariable String code,
                                                       @Valid @RequestBody ManualCriterionRequest request) {
        if (code.startsWith("CUSTOM_")) {
            return ApiResponse.ok("Review criterion saved", customReadiness.answerCustom(orderId, code, request));
        }
        ReviewCriterionCode builtIn;
        try {
            builtIn = ReviewCriterionCode.valueOf(code);
        } catch (IllegalArgumentException invalid) {
            throw new com.ondemandmonitoring.common.exception.ApiException(
                    com.ondemandmonitoring.common.exception.ErrorCode.RESOURCE_NOT_FOUND, "Criterion not found");
        }
        return ApiResponse.ok("Review criterion saved", readiness.answer(orderId, builtIn, request));
    }
}
