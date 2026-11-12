package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.gateway.VerifiedWebhook;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.service.IPaymentWebhookService;
import com.ondemandmonitoring.finance.service.IMissionExecutionActivationService;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.service.IRefundHoldService;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookProcessor implements IPaymentWebhookService {
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final IMissionService missions;
    private final OrderRepository orders;
    private final MissionPaymentMilestoneRepository milestones;
    private final IMissionExecutionActivationService missionActivation;
    @Autowired
    private IDeliveryWorkflowService deliveryWorkflow;
    @Autowired
    private IRefundHoldService refundHoldService;

    @Transactional
    public PaymentWebhookResult process(VerifiedWebhook webhook) {
        String orderId = payments.findOrderIdByTransactionReference(webhook.transactionReference()).orElse(null);
        if (orderId == null) return PaymentWebhookResult.ORDER_NOT_FOUND;
        var order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Payment candidate = payments.findByTransactionReference(webhook.transactionReference()).orElse(null);
        if (candidate != null && webhook.successful() && refundHoldService != null
                && refundHoldService.isOrderOnRefundHold(orderId)
                && candidate.getStatus() != PaymentStatus.SUCCESS
                && candidate.getStatus() != PaymentStatus.REQUIRES_REVIEW) {
            Payment pending = payments.findByTransactionReferenceForUpdate(webhook.transactionReference())
                    .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
            pending.setStatus(PaymentStatus.REQUIRES_REVIEW);
            pending.setProviderTransactionId(webhook.providerTransactionId());
            pending.setPaidAt(Instant.now());
            pending.setFailureReason("Provider confirmed payment after a Deposit refund request; manual reconciliation required");
            return PaymentWebhookResult.LATE_PAYMENT_RECORDED;
        }
        Payment payment = payments.findByTransactionReferenceForUpdate(webhook.transactionReference()).orElse(null);
        if (payment == null || payment.getProvider() != PaymentProvider.VNPAY) {
            return PaymentWebhookResult.ORDER_NOT_FOUND;
        }
        if (!"VND".equalsIgnoreCase(webhook.currency())
                || payment.getAmount().compareTo(webhook.amount()) != 0) {
            return PaymentWebhookResult.INVALID_AMOUNT;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            // Keep handling already-issued legacy links; no current endpoint creates FINAL_PAYMENT rows.
            if (payment.getPaymentType() == PaymentType.FINAL_PAYMENT
                    || payment.getPaymentType() == PaymentType.DEPOSIT
                    || payment.getPaymentType() == PaymentType.MISSION_PAYMENT) {
                Invoice settled = invoices.findByIdForUpdate(payment.getInvoice().getId())
                        .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
                if (payment.getPaymentType() == PaymentType.DEPOSIT) {
                    settled.refreshDepositStatus();
                    missionActivation.activateIfEligible(settled.getOrder().getId());
                }
                if (isFullyPaid(settled)) transitionAfterSettlement(settled.getOrder().getId());
            }
            return PaymentWebhookResult.ALREADY_CONFIRMED;
        }
        if (payment.getStatus() == PaymentStatus.REQUIRES_REVIEW) return PaymentWebhookResult.ALREADY_CONFIRMED;
        if (webhook.successful() && order.getOrderStatus() == OrderStatus.CANCELLED) {
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
                resetMissionPayment(payment);
            }
            return PaymentWebhookResult.IGNORED;
        }

        Invoice invoice = invoices.findByIdForUpdate(payment.getInvoice().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (payment.getPaymentType() == PaymentType.MISSION_PAYMENT) {
            if (payment.getMissionPaymentMilestone() == null) {
                payment.setStatus(PaymentStatus.REQUIRES_REVIEW);
                payment.setProviderTransactionId(webhook.providerTransactionId());
                payment.setPaidAt(Instant.now());
                payment.setFailureReason("Mission payment has no linked milestone; manual review required");
                return PaymentWebhookResult.LATE_PAYMENT_RECORDED;
            }
            var milestone = milestones.findByIdForUpdate(payment.getMissionPaymentMilestone().getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission payment milestone not found"));
            boolean anotherSucceeded = payments.existsByMissionPaymentMilestoneIdAndStatus(
                    milestone.getId(), PaymentStatus.SUCCESS);
            if (anotherSucceeded || milestone.getStatus() == MissionPaymentMilestoneStatus.PAID) {
                payment.setStatus(PaymentStatus.REQUIRES_REVIEW);
                payment.setProviderTransactionId(webhook.providerTransactionId());
                payment.setPaidAt(Instant.now());
                payment.setFailureReason("Provider confirmed a duplicate Mission payment; manual review required");
                return PaymentWebhookResult.LATE_PAYMENT_RECORDED;
            }
            if (milestone.getStatus() != MissionPaymentMilestoneStatus.PENDING
                    || milestone.getAmount().compareTo(payment.getAmount()) != 0
                    || !milestone.getInvoice().getId().equals(invoice.getId())) {
                payment.setStatus(PaymentStatus.REQUIRES_REVIEW);
                payment.setProviderTransactionId(webhook.providerTransactionId());
                payment.setPaidAt(Instant.now());
                payment.setFailureReason("Mission payment amount or state does not match its milestone; manual review required");
                return PaymentWebhookResult.LATE_PAYMENT_RECORDED;
            }
            milestone.setStatus(MissionPaymentMilestoneStatus.PAID);
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setProviderTransactionId(webhook.providerTransactionId());
        payment.setPaidAt(Instant.now());
        payments.flush();

        BigDecimal paid = payments.sumSuccessfulPayments(invoice.getId());
        if (paid.compareTo(invoice.getTotalAmount()) > 0) throw new ApiException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Payment would overpay invoice");
        invoice.setPaidAmount(paid);
        invoice.setRemainingAmount(invoice.getTotalAmount().subtract(paid).max(BigDecimal.ZERO));
        invoice.refreshDepositStatus();
        invoice.setStatus(paid.signum() == 0 ? InvoiceStatus.ISSUED
                : paid.compareTo(invoice.getTotalAmount()) >= 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        if (payment.getPaymentType() == PaymentType.DEPOSIT)
            missionActivation.activateIfEligible(invoice.getOrder().getId());
        if (isFullyPaid(invoice))
            transitionAfterSettlement(invoice.getOrder().getId());
        return PaymentWebhookResult.PROCESSED;
    }

    private void resetMissionPayment(Payment payment) {
        if (payment.getPaymentType() != PaymentType.MISSION_PAYMENT || payment.getMissionPaymentMilestone() == null) return;
        milestones.findByIdForUpdate(payment.getMissionPaymentMilestone().getId()).ifPresent(milestone -> {
            if (milestone.getStatus() == MissionPaymentMilestoneStatus.PENDING)
                milestone.setStatus(MissionPaymentMilestoneStatus.UNPAID);
        });
    }

    private static boolean isFullyPaid(Invoice invoice) {
        return invoice.getStatus() == InvoiceStatus.PAID && invoice.getRemainingAmount() != null
                && invoice.getRemainingAmount().signum() == 0;
    }

    /** Financial settlement commits first; mission state is a separately retryable side effect. */
    private void transitionAfterSettlement(String orderId) {
        Runnable activation = () -> {
            try {
                deliveryWorkflow.completeAcceptedRevisionCase(orderId);
            } catch (RuntimeException exception) {
                log.warn("Payment recorded but accepted Mission deliveries require reconciliation for Order {}", orderId, exception);
            }
            try {
                missions.transitionToPendingConfirmation(orderId);
            } catch (RuntimeException exception) {
                log.warn("Invoice settlement is recorded but Mission activation remains pending for Order {}; recovery will retry",
                        orderId, exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activation.run();
            }
        });
    }
}
