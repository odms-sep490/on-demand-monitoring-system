package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.dto.PaymentResponse;
import com.ondemandmonitoring.finance.enums.PaymentWebhookResult;
import com.ondemandmonitoring.finance.gateway.VerifiedWebhook;
import java.util.List;
import java.util.Map;

/**
 * Application contract for customer payment use cases.
 *
 * <p>Controllers depend on this interface so HTTP concerns stay outside payment
 * persistence, provider signing and invoice state transitions.</p>
 */
public interface IPaymentService {
    PaymentResponse createDeposit(String invoiceId, String clientIp);
    PaymentResponse completeTestDeposit(String invoiceId);
    PaymentResponse completeTestFinal(String invoiceId);
    PaymentResponse createFinal(String invoiceId, String clientIp);
    PaymentResponse get(String paymentId);
    PaymentResponse getByReference(String transactionReference);
    PaymentResponse refreshByReference(String transactionReference, String requestIp);

    /**
     * Manager/Admin: reconciles every still-pending payment of the order with VNPAY, for when the IPN could not reach
     * the server. Returns how many payments were re-checked.
     */
    int refreshOrderPayments(String orderId, String requestIp);
    List<PaymentResponse> list(String invoiceId);
    PaymentWebhookResult processVnPayWebhook(Map<String, String> payload);
    VerifiedWebhook verifyVnPayReturn(Map<String, String> payload);
}
