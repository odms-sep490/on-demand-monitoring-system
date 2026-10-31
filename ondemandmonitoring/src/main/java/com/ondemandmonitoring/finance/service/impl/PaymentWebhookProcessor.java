package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.gateway.VerifiedWebhook;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.finance.service.IPaymentWebhookService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentWebhookProcessor implements IPaymentWebhookService {
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final MissionRepository missions;
    private final IMissionPaymentEligibilityService eligibility;
    private final IDeliveryWorkflowService deliveryWorkflow;
    private final com.ondemandmonitoring.order.repository.OrderRepository orders;

    @Transactional
    public PaymentWebhookResult process(VerifiedWebhook webhook) {
        String orderId = payments.findOrderIdByTransactionReference(webhook.transactionReference()).orElse(null);
        if (orderId == null) return PaymentWebhookResult.ORDER_NOT_FOUND;
        var order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = payments.findByTransactionReferenceForUpdate(webhook.transactionReference()).orElse(null);
        if (payment == null || payment.getProvider() != PaymentProvider.VNPAY) {
            return PaymentWebhookResult.ORDER_NOT_FOUND;
        }
        if (!"VND".equalsIgnoreCase(webhook.currency())
                || payment.getAmount().compareTo(webhook.amount()) != 0) {
            return PaymentWebhookResult.INVALID_AMOUNT;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS) return PaymentWebhookResult.ALREADY_CONFIRMED;
        if (payment.getStatus() == PaymentStatus.REQUIRES_REVIEW) return PaymentWebhookResult.ALREADY_CONFIRMED;
        if (webhook.successful() && order.getOrderStatus() == com.ondemandmonitoring.order.enums.OrderStatus.CANCELLED) {
            payment.setStatus(PaymentStatus.REQUIRES_REVIEW);
            payment.setProviderTransactionId(webhook.providerTransactionId());
            payment.setPaidAt(Instant.now());
            payment.setFailureReason("Provider confirmed payment after order cancellation; manual refund/reconciliation required");
            return PaymentWebhookResult.LATE_PAYMENT_RECORDED;
        }
        if (payment.getStatus() == PaymentStatus.CANCELLED
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.FAILED) {
            return PaymentWebhookResult.ALREADY_CONFIRMED;
        }
        if (!webhook.successful()) {
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setProviderTransactionId(webhook.providerTransactionId());
                payment.setFailureReason("VNPAY response=" + webhook.responseCode()
                        + ", transactionStatus=" + webhook.transactionStatus());
            }
            return PaymentWebhookResult.IGNORED;
        }

        Invoice invoice = invoices.findByIdForUpdate(payment.getInvoice().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setProviderTransactionId(webhook.providerTransactionId());
        payment.setPaidAt(Instant.now());
        payments.flush();

        BigDecimal paid = payments.sumSuccessfulPayments(invoice.getId());
        if (paid.compareTo(invoice.getTotalAmount()) > 0) throw new ApiException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Payment would overpay invoice");
        invoice.setPaidAmount(paid);
        invoice.setRemainingAmount(invoice.getTotalAmount().subtract(paid).max(BigDecimal.ZERO));
        invoice.setStatus(paid.signum() == 0 ? InvoiceStatus.ISSUED
                : paid.compareTo(invoice.getTotalAmount()) >= 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        for (String missionId : missions.findIdsByOrderIdOrderById(invoice.getOrder().getId()).stream().sorted().toList()) {
            var mission = missions.findByIdForUpdate(missionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
            eligibility.unlockIfEligible(invoice, mission);
        }
        if (payment.getPaymentType() == PaymentType.FINAL_PAYMENT && invoice.getStatus() == InvoiceStatus.PAID) {
            deliveryWorkflow.confirmFinalPayment(invoice.getOrder().getId());
        }
        return PaymentWebhookResult.PROCESSED;
    }
}
