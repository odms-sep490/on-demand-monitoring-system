package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.dto.PaymentResponse;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.gateway.PaymentLinkResult;
import com.ondemandmonitoring.finance.mapper.FinanceMapper;
import com.ondemandmonitoring.finance.record.PaymentReconciliationContext;
import com.ondemandmonitoring.finance.record.PreparedPayment;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.*;
import com.ondemandmonitoring.finance.util.PaymentReferenceGenerator;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.service.IRefundHoldService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentPersistenceService implements IPaymentPersistenceService {
    private final InvoiceRepository invoices;
    private final MissionPaymentMilestoneRepository milestones;
    private final PaymentRepository payments;
    private final AuthenticatedUserResolver currentUser;
    private final IMissionPaymentEligibilityService eligibility;
    private final PaymentReferenceGenerator referenceGenerator;
    private final OrderRepository orders;
    private final Clock clock;
    @Autowired
    private IRefundHoldService refundHoldService;

    @Transactional
    public PreparedPayment prepareDeposit(String invoiceId) {
        PaymentType type = PaymentType.DEPOSIT;
        String orderId = invoices.findOrderIdById(invoiceId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (refundHoldService != null) refundHoldService.requireOrderNotOnRefundHold(orderId);
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!invoice.getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (invoice.getStatus() == InvoiceStatus.PAID || invoice.getRemainingAmount().signum() == 0) {
            throw new ApiException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE);
        }
        if (invoice.getDepositExpiresAt() != null
                && !Instant.now(clock).isBefore(invoice.getDepositExpiresAt())) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "Deposit payment deadline has expired");
        }
        if (eligibility.isDepositSatisfied(invoice)) {
            throw new ApiException(ErrorCode.DEPOSIT_ALREADY_SATISFIED);
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

        BigDecimal amount = invoice.getDepositAmount().subtract(invoice.getPaidAmount()).max(BigDecimal.ZERO);
        if (amount.signum() <= 0) throw new ApiException(ErrorCode.DEPOSIT_ALREADY_SATISFIED);
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
    public PreparedPayment prepareMissionPayment(String milestoneId) {
        String orderId = milestones.findById(milestoneId)
                .map(milestone -> milestone.getInvoice().getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission payment milestone not found"));
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (refundHoldService != null) refundHoldService.requireOrderNotOnRefundHold(orderId);
        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        invoice = invoices.findByIdForUpdate(invoice.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!invoice.getOrder().getCustomer().getId().equals(currentUser.getCurrentUserId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (invoice.getStatus() == InvoiceStatus.PAID || invoice.getRemainingAmount().signum() == 0)
            throw new ApiException(ErrorCode.INVOICE_ALREADY_PAID);
        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE)
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE);
        if (!eligibility.isDepositSatisfied(invoice))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "The Deposit must be paid before Mission payments");

        MissionPaymentMilestone milestone = milestones.findByIdForUpdate(milestoneId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission payment milestone not found"));
        if (!milestone.getInvoice().getId().equals(invoice.getId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (milestone.getHandedOverAt() == null
                || milestone.getStatus() == MissionPaymentMilestoneStatus.NOT_YET_DUE)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This Mission has not been handed over for payment");
        if (milestone.getStatus() == MissionPaymentMilestoneStatus.PAID)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This Mission milestone is already paid");
        if (milestone.getStatus() != MissionPaymentMilestoneStatus.UNPAID
                && milestone.getStatus() != MissionPaymentMilestoneStatus.PENDING)
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "This Mission milestone is not payable");

        var active = payments.findFirstByMissionPaymentMilestoneIdAndProviderAndStatusInOrderByCreatedAtDesc(
                milestoneId, PaymentProvider.VNPAY, List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING));
        if (active.isPresent()) {
            Payment existing = active.get();
            if (existing.getPaymentUrl() != null) {
                return new PreparedPayment(existing.getId(), existing.getTransactionReference(), existing.getAmount(),
                        description(PaymentType.MISSION_PAYMENT, existing.getTransactionReference()), true,
                        FinanceMapper.payment(existing));
            }
            throw new ApiException(ErrorCode.ACTIVE_PAYMENT_EXISTS);
        }
        if (milestone.getPaymentExpiresAt() != null
                && !Instant.now(clock).isBefore(milestone.getPaymentExpiresAt())) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "Mission payment deadline has expired");
        }
        if (payments.existsByMissionPaymentMilestoneIdAndStatus(milestoneId, PaymentStatus.SUCCESS))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A successful payment already exists for this Mission");
        if (milestone.getStatus() != MissionPaymentMilestoneStatus.UNPAID)
            throw new ApiException(ErrorCode.ACTIVE_PAYMENT_EXISTS);

        BigDecimal amount = milestone.getAmount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 0
                || !"VND".equals(milestone.getCurrency()) || amount.compareTo(invoice.getRemainingAmount()) > 0)
            throw new ApiException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Mission payment amount is not payable against this Invoice");
        BigDecimal activeAmounts = payments.sumActivePaymentAmountsByInvoiceId(invoice.getId());
        if (activeAmounts.add(amount).compareTo(invoice.getRemainingAmount()) > 0)
            throw new ApiException(ErrorCode.ACTIVE_PAYMENT_EXISTS, "Other active payments already reserve the Invoice balance");

        Payment payment = new Payment();
        payment.setInvoice(invoice);
        payment.setMissionPaymentMilestone(milestone);
        payment.setTransactionReference(uniqueReference());
        payment.setPaymentCode(Long.valueOf(payment.getTransactionReference()));
        payment.setPaymentType(PaymentType.MISSION_PAYMENT);
        payment.setAmount(amount.setScale(0, RoundingMode.UNNECESSARY));
        payment.setCurrency("VND");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setProvider(PaymentProvider.VNPAY);
        milestone.setStatus(MissionPaymentMilestoneStatus.PENDING);
        payment = payments.saveAndFlush(payment);
        return new PreparedPayment(payment.getId(), payment.getTransactionReference(), payment.getAmount(),
                description(PaymentType.MISSION_PAYMENT, payment.getTransactionReference()), false,
                FinanceMapper.payment(payment));
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
        payments.findByIdForUpdate(paymentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(reason == null ? "Payment provider error" : reason.substring(0, Math.min(1000, reason.length())));
                if (payment.getPaymentType() == PaymentType.MISSION_PAYMENT && payment.getMissionPaymentMilestone() != null) {
                    MissionPaymentMilestone milestone = milestones.findByIdForUpdate(payment.getMissionPaymentMilestone().getId()).orElse(null);
                    if (milestone != null && milestone.getStatus() == MissionPaymentMilestoneStatus.PENDING)
                        milestone.setStatus(MissionPaymentMilestoneStatus.UNPAID);
                }
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
        return switch (type) {
            case DEPOSIT -> "OMSS DEP " + reference;
            case MISSION_PAYMENT -> "OMSS MIS " + reference;
            case FINAL_PAYMENT -> "OMSS LEGACY " + reference;
            case REFUND -> "OMSS REF " + reference;
        };
    }
}
