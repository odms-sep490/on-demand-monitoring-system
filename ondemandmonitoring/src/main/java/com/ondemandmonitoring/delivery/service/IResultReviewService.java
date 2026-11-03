package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.ResultReviewResponse;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionSubmittedResponse;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionTicketReviewResponse;

public interface IResultReviewService {
    ResultReviewResponse getOrderReview(String orderId, int page, int size);
    com.ondemandmonitoring.delivery.dto.response.ResultReviewHistoryResponse getHistory(String orderId, int page, int size);
    ResultRevisionSubmittedResponse submit(String orderId, RevisionRequest request);
    ResultRevisionTicketReviewResponse getTicketReview(String ticketId);
}
