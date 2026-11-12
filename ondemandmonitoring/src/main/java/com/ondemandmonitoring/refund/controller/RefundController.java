package com.ondemandmonitoring.refund.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.refund.dto.RecordRefundTransferAttemptRequest;
import com.ondemandmonitoring.refund.dto.RefundReceiptAccessResponse;
import com.ondemandmonitoring.refund.dto.RefundDecisionRequest;
import com.ondemandmonitoring.refund.dto.RefundResponse;
import com.ondemandmonitoring.refund.dto.RefundAdminSummaryResponse;
import com.ondemandmonitoring.refund.dto.RequestDepositRefundRequest;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import com.ondemandmonitoring.refund.service.RefundService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RefundController {
    private final RefundService refunds;

    @PostMapping("/manager/orders/{orderId}/deposit-refunds")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<RefundResponse>> requestDepositRefund(
            @PathVariable String orderId,
            @Valid @RequestBody RequestDepositRefundRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Deposit refund requested",
                refunds.requestDepositRefund(orderId, request)));
    }

    @GetMapping("/manager/orders/{orderId}/deposit-refund")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<RefundResponse>> getManagerRefund(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Refund loaded", refunds.getRefundForOrder(orderId)));
    }

    @GetMapping("/orders/{orderId}/deposit-refund")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<RefundResponse>> getCustomerRefund(@PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Refund loaded", refunds.getRefundForOrder(orderId)));
    }

    @GetMapping("/admin/deposit-refunds")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<RefundResponse>>> listRefunds(
            @RequestParam(required = false) RefundStatus status) {
        return ResponseEntity.ok(ApiResponse.ok("Refunds loaded", refunds.listRefunds(status)));
    }

    @GetMapping("/admin/deposit-refunds/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundAdminSummaryResponse>> getAdminSummary() {
        return ResponseEntity.ok(ApiResponse.ok("Payment and refund totals loaded", refunds.getAdminSummary()));
    }

    @GetMapping("/admin/deposit-refunds/{refundId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> getAdminRefund(@PathVariable String refundId) {
        return ResponseEntity.ok(ApiResponse.ok("Refund loaded", refunds.getAdminRefund(refundId)));
    }

    @PostMapping("/admin/deposit-refunds/{refundId}/processing")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> startProcessing(@PathVariable String refundId) {
        return ResponseEntity.ok(ApiResponse.ok("Refund processing started", refunds.startProcessing(refundId)));
    }

    @PostMapping("/admin/deposit-refunds/{refundId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> rejectRefund(
            @PathVariable String refundId,
            @Valid @RequestBody RefundDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Deposit refund rejected",
                refunds.rejectRefund(refundId, request)));
    }

    @PostMapping("/admin/deposit-refunds/{refundId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> cancelRefund(
            @PathVariable String refundId,
            @Valid @RequestBody RefundDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Deposit refund cancelled",
                refunds.cancelRefund(refundId, request)));
    }

    @PostMapping("/admin/deposit-refunds/{refundId}/attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> recordAttempt(
            @PathVariable String refundId,
            @Valid @RequestBody RecordRefundTransferAttemptRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Transfer attempt recorded",
                refunds.recordTransferAttempt(refundId, request)));
    }

    @PostMapping(value = "/admin/deposit-refunds/{refundId}/attempts/{attemptId}/receipts",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> uploadReceipt(
            @PathVariable String refundId,
            @PathVariable String attemptId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok("Transfer receipt uploaded",
                refunds.uploadReceipt(refundId, attemptId, file)));
    }

    @PostMapping("/admin/deposit-refunds/{refundId}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RefundResponse>> confirmRefund(@PathVariable String refundId) {
        return ResponseEntity.ok(ApiResponse.ok("Deposit refund confirmed", refunds.confirmRefund(refundId)));
    }

    @GetMapping("/deposit-refunds/{refundId}/receipts/{receiptId}/access")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RefundReceiptAccessResponse>> getReceiptAccess(
            @PathVariable String refundId,
            @PathVariable String receiptId) {
        return ResponseEntity.ok(ApiResponse.ok("Receipt access granted",
                refunds.getReceiptAccess(refundId, receiptId)));
    }
}
