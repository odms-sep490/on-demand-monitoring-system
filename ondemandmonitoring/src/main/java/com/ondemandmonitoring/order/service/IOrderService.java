package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.order.dto.request.OrderAdditionalInformationRequest;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.request.OrderCustomerUpdateRequest;
import com.ondemandmonitoring.order.dto.request.FlightAreaReviewRequest;
import com.ondemandmonitoring.order.dto.request.LegalReviewResolutionRequest;
import com.ondemandmonitoring.order.dto.request.PermitCancellationProposalRequest;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.OrderStatus;

import java.util.List;

public interface IOrderService {

    OrderCreateResponse createOrder(OrderCreateRequest request);

    OrderCreateResponse approveOrder(String orderId);

    OrderCreateResponse reviewFlightArea(String orderId, FlightAreaReviewRequest request);

    OrderCreateResponse resolveLegalReview(String orderId, LegalReviewResolutionRequest request);

    OrderCreateResponse cancelAfterPermitRejection(String orderId, PermitCancellationProposalRequest request);

    void rejectOrder(String orderId, String reason);

    void requestMoreInformation(String orderId, String reason, String requestedUpdateScope);

    OrderCreateResponse submitAdditionalInformation(String orderId,
            OrderAdditionalInformationRequest request);

    OrderCreateResponse updateCustomerOrder(String orderId,
            OrderCustomerUpdateRequest request);

    OrderCreateResponse getOrderById(String orderId);

    List<OrderCreateResponse> getPendingOrders();

    List<OrderCreateResponse> getApprovedOrders();

    /** Manager/Admin: every order, newest first, optionally limited to one status. */
    List<OrderCreateResponse> getManagedOrders(OrderStatus status);

    List<OrderCreateResponse> getMyOrders(OrderStatus status);
}
