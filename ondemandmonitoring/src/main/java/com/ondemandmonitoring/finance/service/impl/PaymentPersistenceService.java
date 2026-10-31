package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.dto.PaymentResponse;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.gateway.PaymentLinkResult;
import com.ondemandmonitoring.finance.mapper.FinanceMapper;
import com.ondemandmonitoring.finance.record.PaymentReconciliationContext;
import com.ondemandmonitoring.finance.record.PreparedPayment;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.*;
import com.ondemandmonitoring.finance.util.PaymentReferenceGenerator;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentPersistenceService implements IPaymentPersistenceService {
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final MissionRepository missions;
    private final AuthenticatedUserResolver currentUser;
    private final IMissionPaymentEligibilityService eligibility;
    private final PaymentReferenceGenerator referenceGenerator;
    private final IDeliveryWorkflowService deliveryWorkflow;
    private final com.ondemandmonitoring.order.repository.OrderRepository orders;
    private final Clock clock;

    @Transactional
    public PreparedPayment prepare(String invoiceId, PaymentType type) {
        String orderId = invoices.findOrderIdById(invoiceId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!invoice.getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (invoice.getOrder().getLegalReviewStatus() != LegalReviewStatus.NOT_REQUIRED
                && invoice.getOrder().getLegalReviewStatus() != LegalReviewStatus.APPROVED
                && invoice.getOrder().getLegalReviewStatus() != LegalReviewStatus.PENDING_PERMIT) {
            throw new ApiException(ErrorCode.ORDER_LEGAL_REVIEW_NOT_READY);
        }
        if (invoice.getStatus() == InvoiceStatus.PAID || invoice.getRemainingAmount().signum() == 0) {
            throw new ApiException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.REFUNDED) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE);
        }
        if (type == PaymentType.DEPOSIT && invoice.getOrder().getDepositExpiresAt() != null
                && !Instant.now(clock).isBefore(invoice.getOrder().getDepositExpiresAt())) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "Deposit payment deadline has expired");
        }
        if (type == PaymentType.DEPOSIT && eligibility.isDepositSatisfied(invoice)) {
            throw new ApiException(ErrorCode.DEPOSIT_ALREADY_SATISFIED);
        }
        if (type == PaymentType.FINAL_PAYMENT) {
            deliveryWorkflow.requireFinalPaymentEligible(invoice.getOrder().getId());
        }
        var active = payments.findFirstByInvoiceIdAndPaymentTypeAndProviderAndStatusInOrderByCreatedAtDesc(
                invoiceId, type, PaymentProvider.VNPAY,
                List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING));
        if (active.isPresent() && active.get().getPaymentUrl() != null) {
            Payment existing = active.get();
            return new PreparedPayment(existing.getId(), existing.getTransactionReference(), existing.getAmount(),
                    description(type, existing.getTransactionReference()), true, FinanceMapper.payment(existing));
        }
        if (active.isPresent()) throw new ApiException(ErrorCode.ACTIVE_PAYMENT_EXISTS);

        BigDecimal amount = type == PaymentType.DEPOSIT
                ? invoice.getDepositAmount().subtract(invoice.getPaidAmount()).max(BigDecimal.ZERO)
                : invoice.getRemainingAmount();
        if (amount.signum() <= 0) throw new ApiException(type == PaymentType.DEPOSIT
                ? ErrorCode.DEPOSIT_ALREADY_SATISFIED : ErrorCode.INVOICE_ALREADY_PAID);
        Payment payment = new Payment();
        payment.setInvoice(invoice);
        String reference = uniqueReference();
        payment.setTransactionReference(reference);
        payment.setPaymentCode(Long.valueOf(reference));
        payment.setPaymentType(type);
        payment.setAmount(amount.setScale(0, RoundingMode.UNNECESSARY));
        payment.setCurrency("VND");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setProvider(PaymentProvider.VNPAY);
        payment = payments.saveAndFlush(payment);
        return new PreparedPayment(payment.getId(), payment.getTransactionReference(), payment.getAmount(),
                description(type, payment.getTransactionReference()), false, FinanceMapper.payment(payment));
    }

    @Transactional
    public PaymentResponse attachLink(String paymentId, PaymentLinkResult link) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.PENDING) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        payment.setProviderTransactionId(link.providerTransactionId());
        payment.setPaymentUrl(link.paymentUrl());
        payment.setProviderRequestDate(link.providerRequestDate());
        return FinanceMapper.payment(payment);
    }

    @Transactional
    public void fail(String paymentId, String reason) {
        payments.findById(paymentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(reason == null ? "Payment provider error" : reason.substring(0, Math.min(1000, reason.length())));
            }
        });
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(String paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.getInvoice().getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return FinanceMapper.payment(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String transactionReference) {
        Payment payment = payments.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.getInvoice().getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return FinanceMapper.payment(payment);
    }

    @Transactional(readOnly = true)
    public PaymentReconciliationContext reconciliationContext(String transactionReference) {
        Payment payment = payments.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.getInvoice().getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        if (payment.getProvider() != PaymentProvider.VNPAY) {
            throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "Only VNPAY payments can be reconciled");
        }
        return new PaymentReconciliationContext(payment.getTransactionReference(),
                payment.getProviderTransactionId(), payment.getProviderRequestDate(), payment.getPaymentUrl());
    }

    @Transactional(readOnly = true)
    public List<String> pendingReferencesForOrder(String orderId) {
        var invoice = invoices.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        return payments.findAllByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).stream()
                .filter(payment -> payment.getProvider() == PaymentProvider.VNPAY)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING)
                .map(Payment::getTransactionReference).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByReferenceForStaff(String transactionReference) {
        return FinanceMapper.payment(payments.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public PaymentReconciliationContext reconciliationContextForStaff(String transactionReference) {
        Payment payment = payments.findByTransactionReference(transactionReference)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getProvider() != PaymentProvider.VNPAY) {
            throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "Only VNPAY payments can be reconciled");
        }
        return new PaymentReconciliationContext(payment.getTransactionReference(),
                payment.getProviderTransactionId(), payment.getProviderRequestDate(), payment.getPaymentUrl());
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(String invoiceId) {
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!invoice.getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        return payments.findAllByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream().map(FinanceMapper::payment).toList();
    }

    private String uniqueReference() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = referenceGenerator.generate();
            Long code = Long.valueOf(candidate);
            if (!payments.existsByTransactionReference(candidate) && !payments.existsByPaymentCode(code)) return candidate;
        }
        throw new ApiException(ErrorCode.CONCURRENT_UPDATE, "Unable to allocate a unique payment reference");
    }

    private String description(PaymentType type, String reference) {
        return (type == PaymentType.DEPOSIT ? "OMSS DEP " : "OMSS FINAL ") + reference;
    }
}
