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
    private final com.ondemandmonitoring.delivery.service.IResultRevisionCaseService revisionCases;

    @GetMapping("/mission-deliveries")
    public ApiResponse<java.util.List<com.ondemandmonitoring.delivery.dto.response.MissionDeliverySummary>> missions(@PathVariable String orderId) {
        return ApiResponse.ok(delivery.missionDeliveries(orderId));
    }

    @GetMapping("/result-review")
    public ApiResponse<ResultReviewResponse> resultReview(@PathVariable String orderId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) String missionId) {
        return ApiResponse.ok(missionId == null ? resultReview.getOrderReview(orderId, page, size) : resultReview.getOrderReview(orderId, missionId, page, size));
    }

    @GetMapping("/result-review/history")
    public ApiResponse<com.ondemandmonitoring.delivery.dto.response.ResultReviewHistoryResponse> resultReviewHistory(
            @PathVariable String orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String missionId) {
        return ApiResponse.ok(missionId == null ? resultReview.getHistory(orderId, page, size) : resultReview.getHistory(orderId, missionId, page, size));
    }

    @GetMapping("/delivery")
    public ApiResponse<DeliveryResponse> get(@PathVariable String orderId, @RequestParam(required = false) String missionId) {
        return ApiResponse.ok(missionId == null ? delivery.get(orderId) : delivery.get(orderId, missionId));
    }

    @GetMapping("/deliverables/preview")
    public ApiResponse<DeliveryResponse> previews(@PathVariable String orderId, @RequestParam(required = false) String missionId) {
        return ApiResponse.ok(missionId == null ? delivery.previews(orderId) : delivery.previews(orderId, missionId));
    }

    @GetMapping("/deliverables")
    public ApiResponse<DeliveryResponse> originals(@PathVariable String orderId, @RequestParam(required = false) String missionId) {
        return ApiResponse.ok(missionId == null ? delivery.originals(orderId) : delivery.originals(orderId, missionId));
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

    @PostMapping("/result-revision-case/items/{caseItemId}/acknowledge-out-of-scope")
    public ApiResponse<com.ondemandmonitoring.delivery.dto.response.ResultRevisionCaseResponse> acknowledgeOutOfScope(
            @PathVariable String orderId, @PathVariable String caseItemId) {
        return ApiResponse.ok(revisionCases.acknowledgeOutOfScope(orderId, caseItemId));
    }
}
