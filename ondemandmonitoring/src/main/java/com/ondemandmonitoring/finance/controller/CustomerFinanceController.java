package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.finance.dto.*;
import com.ondemandmonitoring.finance.service.IPaymentService;
import com.ondemandmonitoring.finance.service.IQuoteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** HTTP adapter only; business rules are delegated to finance service contracts. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerFinanceController {
    private final IQuoteService quoteService;
    private final IPaymentService paymentService;

    @GetMapping("/orders/{orderId}/quote")
    public ApiResponse<CustomerQuoteResponse> quote(@PathVariable String orderId) {
        return ApiResponse.ok(quoteService.getCurrentForCustomer(orderId));
    }

    @PostMapping("/orders/{orderId}/quotes/{quoteId}/accept")
    public ApiResponse<InvoiceResponse> accept(@PathVariable String orderId, @PathVariable String quoteId) {
        return ApiResponse.ok("Quote accepted and invoice issued", quoteService.accept(orderId, quoteId));
    }

    @GetMapping("/orders/{orderId}/invoice")
    public ApiResponse<InvoiceResponse> invoice(@PathVariable String orderId) {
        return ApiResponse.ok(quoteService.getInvoiceByOrder(orderId));
    }

    @PostMapping("/invoices/{invoiceId}/payments/deposit")
    public ApiResponse<PaymentResponse> deposit(@PathVariable String invoiceId, HttpServletRequest request) {
        return ApiResponse.ok("Deposit payment link ready", paymentService.createDeposit(invoiceId, request.getRemoteAddr()));
    }

    @PostMapping("/invoices/{invoiceId}/payments/test-deposit")
    public ApiResponse<PaymentResponse> testDeposit(@PathVariable String invoiceId) {
        return ApiResponse.ok("Test deposit completed", paymentService.completeTestDeposit(invoiceId));
    }

    @PostMapping("/invoices/{invoiceId}/payments/final")
    public ApiResponse<PaymentResponse> finalPayment(@PathVariable String invoiceId, HttpServletRequest request) {
        return ApiResponse.ok("Final payment link ready", paymentService.createFinal(invoiceId, request.getRemoteAddr()));
    }

    @PostMapping("/invoices/{invoiceId}/payments/test-final")
    public ApiResponse<PaymentResponse> testFinal(@PathVariable String invoiceId) {
        return ApiResponse.ok("Test final payment completed", paymentService.completeTestFinal(invoiceId));
    }

    @GetMapping("/payments/{paymentId}")
    public ApiResponse<PaymentResponse> payment(@PathVariable String paymentId) {
        return ApiResponse.ok(paymentService.get(paymentId));
    }

    @GetMapping("/payments/by-reference/{transactionReference}")
    public ApiResponse<PaymentResponse> paymentByReference(@PathVariable String transactionReference) {
        return ApiResponse.ok(paymentService.getByReference(transactionReference));
    }

    @PostMapping("/payments/by-reference/{transactionReference}/refresh")
    public ApiResponse<PaymentResponse> refreshPaymentByReference(
            @PathVariable String transactionReference, HttpServletRequest request) {
        return ApiResponse.ok("Payment status reconciled",
                paymentService.refreshByReference(transactionReference, request.getRemoteAddr()));
    }

    @GetMapping("/invoices/{invoiceId}/payments")
    public ApiResponse<List<PaymentResponse>> payments(@PathVariable String invoiceId) {
        return ApiResponse.ok(paymentService.list(invoiceId));
    }
}
