package com.ondemandmonitoring.order.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.request.OrderAdditionalInformationRequest;
import com.ondemandmonitoring.order.dto.request.FlightAreaReviewRequest;
import com.ondemandmonitoring.order.dto.request.LegalReviewResolutionRequest;
import com.ondemandmonitoring.order.dto.request.PermitCancellationProposalRequest;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;

import java.util.List;

@Tag(name = "Order Management", description = "APIs for creating and managing monitoring orders")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderController {

    IOrderService orderService;

    @Operation(summary = "Create a new order", description = "Creates a new monitoring order after validating customer login, service existence, preferred time existence, media attributes, and location point inside zone polygon")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> createOrder(
            @Valid @RequestBody OrderCreateRequest request) {
        OrderCreateResponse response = orderService.createOrder(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created("Order created successfully", response));
    }

    @Operation(summary = "Get my orders", description = "Customer views their own monitoring orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<OrderCreateResponse>>> getMyOrders(
            @RequestParam(required = false) OrderStatus status) {
        return ResponseEntity
                .ok(ApiResponse.ok("Customer orders retrieved successfully", orderService.getMyOrders(status)));
    }

    @Operation(summary = "Get order details", description = "Retrieves details of a specific order by ID")
    @GetMapping("/{orderId}")
    @PreAuthorize("@orderAuthorizationService.canViewOrder(#orderId)")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> getOrderById(@PathVariable String orderId) {
        OrderCreateResponse response = orderService.getOrderById(orderId);
        return ResponseEntity.ok(ApiResponse.ok("Order details retrieved successfully", response));
    }

    @Operation(summary = "Get order resource preview", description = "Retrieves resource preview for an order (returns null if none)")
    @GetMapping("/{orderId}/resource-preview")
    @PreAuthorize("@orderAuthorizationService.canViewOrder(#orderId)")
    public ResponseEntity<ApiResponse<Object>> getResourcePreview(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Resource preview retrieved", null));
    }

    @Operation(summary = "Approve an order", description = "Manager approves an order. Mission scheduling is assigned later by staff.")
    @PostMapping("/{orderId}/approve")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> approveOrder(@PathVariable String orderId) {
        OrderCreateResponse order = orderService.approveOrder(orderId);
        return ResponseEntity.ok(ApiResponse.ok("Order approved successfully", order));
    }

    @Operation(summary = "Record preliminary flight-area review")
    @PostMapping("/{orderId}/flight-area-review")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> reviewFlightArea(
            @PathVariable String orderId,
            @Valid @RequestBody FlightAreaReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Flight-area review recorded",
                orderService.reviewFlightArea(orderId, request)));
    }

    @Operation(summary = "Record the result of external permit verification")
    @PostMapping("/{orderId}/legal-review/resolve")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> resolveLegalReview(
            @PathVariable String orderId,
            @Valid @RequestBody LegalReviewResolutionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Legal review resolved",
                orderService.resolveLegalReview(orderId, request)));
    }

    @Operation(summary = "Cancel an order after an unrecoverable permit rejection")
    @PostMapping("/{orderId}/permit-cancellation")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> cancelAfterPermitRejection(
            @PathVariable String orderId,
            @Valid @RequestBody PermitCancellationProposalRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled after permit rejection",
                orderService.cancelAfterPermitRejection(orderId, request)));
    }

    @Operation(summary = "Submit approval decision", description = "Manager submits rejection or need-info decision with reason")
    @PostMapping("/{orderId}/approval")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> submitApproval(
            @PathVariable String orderId,
            @RequestBody java.util.Map<String, String> body) {
        String decision = body != null ? body.get("decision") : null;
        String reason = body != null ? body.get("reason") : null;
        String requestedUpdateScope = body != null ? body.get("requestedUpdateScope") : null;
        if (!"REJECTED".equalsIgnoreCase(decision) && !"NEED_INFO".equalsIgnoreCase(decision)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Decision must be REJECTED or NEED_INFO");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Rejection reason is required");
        }
        if ("NEED_INFO".equalsIgnoreCase(decision)) {
            orderService.requestMoreInformation(orderId, reason, requestedUpdateScope);
        } else {
            orderService.rejectOrder(orderId, reason);
        }
        return ResponseEntity.ok(ApiResponse.ok("Approval decision recorded successfully", null));
    }

    @Operation(summary = "Submit requested additional information")
    @PostMapping("/{orderId}/additional-information")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> submitAdditionalInformation(
            @PathVariable String orderId,
            @Valid @RequestBody OrderAdditionalInformationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Additional information submitted",
                orderService.submitAdditionalInformation(orderId, request)));
    }

    @Operation(summary = "Update my Order while it is still under review")
    @PatchMapping("/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderCreateResponse>> updateCustomerOrder(
            @PathVariable String orderId,
            @Valid @RequestBody com.ondemandmonitoring.order.dto.request.OrderCustomerUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Order updated successfully",
                orderService.updateCustomerOrder(orderId, request)));
    }

    @Operation(summary = "Get pending orders", description = "Manager views pending orders")
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<java.util.List<OrderCreateResponse>>> getPendingOrders() {
        return ResponseEntity.ok(ApiResponse.ok("Pending orders retrieved", orderService.getPendingOrders()));
    }

    @Operation(summary = "List all orders", description = "Manager views every order, newest first, optionally filtered by status")
    @GetMapping("/managed")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<java.util.List<OrderCreateResponse>>> getManagedOrders(
            @org.springframework.web.bind.annotation.RequestParam(required = false) com.ondemandmonitoring.order.enums.OrderStatus status) {
        return ResponseEntity.ok(ApiResponse.ok("Orders retrieved", orderService.getManagedOrders(status)));
    }

    @Operation(summary = "Get approved orders", description = "Staff views approved orders that can be scheduled into missions")
    @GetMapping("/approved")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<java.util.List<OrderCreateResponse>>> getApprovedOrders() {
        return ResponseEntity.ok(ApiResponse.ok("Approved orders retrieved", orderService.getApprovedOrders()));
    }
}
