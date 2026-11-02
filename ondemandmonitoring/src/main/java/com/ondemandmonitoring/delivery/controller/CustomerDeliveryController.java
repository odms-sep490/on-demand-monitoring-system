package com.ondemandmonitoring.delivery.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import com.ondemandmonitoring.delivery.dto.request.AcceptResultRequest;
import com.ondemandmonitoring.delivery.dto.response.ResultReviewResponse;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionSubmittedResponse;
import com.ondemandmonitoring.delivery.service.IResultReviewService;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Customer-facing endpoints for reviewing and accepting delivered results. */
@RestController
@RequestMapping("/api/orders/{orderId}")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerDeliveryController {
    private final IDeliveryWorkflowService delivery;
    private final IResultReviewService resultReview;

    @GetMapping("/result-review")
    public ApiResponse<ResultReviewResponse> resultReview(@PathVariable String orderId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(resultReview.getOrderReview(orderId, page, size));
    }

    @GetMapping("/delivery")
    public ApiResponse<DeliveryResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(delivery.get(orderId));
    }

    @GetMapping("/deliverables/preview")
    public ApiResponse<DeliveryResponse> previews(@PathVariable String orderId) {
        return ApiResponse.ok(delivery.previews(orderId));
    }

    @GetMapping("/deliverables")
    public ApiResponse<DeliveryResponse> originals(@PathVariable String orderId) {
        return ApiResponse.ok(delivery.originals(orderId));
    }

    @PostMapping("/result/accept")
    public ApiResponse<DeliveryResponse> accept(@PathVariable String orderId, @RequestBody(required = false) AcceptResultRequest request) {
        return ApiResponse.ok("Result accepted", delivery.acceptResult(orderId,
                request == null ? null : request.expectedReviewRoundId(), request == null ? null : request.expectedRoundVersion()));
    }

    @PostMapping("/result/revision-request")
    public ApiResponse<ResultRevisionSubmittedResponse> revision(
            @PathVariable String orderId,
            @Valid @RequestBody RevisionRequest request) {
        return ApiResponse.ok("Revision requested", resultReview.submit(orderId, request));
    }
}
