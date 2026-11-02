package com.ondemandmonitoring.support.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionTicketReviewResponse;
import com.ondemandmonitoring.delivery.service.IResultReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support/tickets")
@RequiredArgsConstructor
public class SupportResultReviewController {
    private final IResultReviewService resultReview;

    @GetMapping("/{ticketId}/result-review")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ResultRevisionTicketReviewResponse> get(@PathVariable String ticketId) {
        return ApiResponse.ok(resultReview.getTicketReview(ticketId));
    }
}
