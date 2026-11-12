package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderCancellationReason;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One-record transactions used by the scheduler; order-first locks match acceptance and IPN processing. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommercialExpirationProcessor {
    private static final String SYSTEM_ACTOR = "SYSTEM_COMMERCIAL_EXPIRATION";
    private final OrderRepository orders;
    private final QuoteRepository quotes;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final MissionPaymentMilestoneRepository milestones;
    private final MissionRepository missions;
    private final PaymentProperties properties;
    private final Clock clock;

    @Transactional
    public boolean expireQuote(String quoteId) {
        Quote candidate = quotes.findById(quoteId).orElse(null);
        if (candidate == null) return false;
        Order order = orders.findByIdForUpdate(candidate.getOrder().getId()).orElse(null);
        Quote quote = quotes.findByIdForUpdate(quoteId).orElse(null);
        Instant now = Instant.now(clock);
        if (order == null || quote == null || quote.getStatus() != QuoteStatus.APPROVED
                || quote.getExpiresAt() == null || now.isBefore(quote.getExpiresAt())
                || order.getOrderStatus() != OrderStatus.APPROVED) return false;
        List<Mission> affected = cancellableMissions(order.getId());
        if (affected == null) return false;
        quote.setStatus(QuoteStatus.EXPIRED);
        cancel(order, affected, OrderCancellationReason.QUOTATION_EXPIRED, now);
        return true;
    }

    @Transactional
    public boolean expireDeposit(String invoiceId) {
        Invoice candidate = invoices.findById(invoiceId).orElse(null);
        if (candidate == null) return false;
        Order order = orders.findByIdForUpdate(candidate.getOrder().getId()).orElse(null);
        if (order == null || order.getOrderStatus() != OrderStatus.APPROVED
                || candidate.getDepositExpiresAt() == null) return false;
        Instant now = Instant.now(clock);
        if (now.isBefore(candidate.getDepositExpiresAt())) return false;

        // Lock the commercial aggregate in deterministic order before applying expiration.
        List<Payment> lockedPayments = payments.findAllByInvoiceIdForUpdate(invoiceId);
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() == InvoiceStatus.CANCELLED
                || invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getDepositExpiresAt() == null || now.isBefore(invoice.getDepositExpiresAt())
                || invoice.effectiveDepositStatus() != DepositStatus.WAITING_DEPOSIT)
            return false;
        BigDecimal successful = lockedPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS && p.getPaymentType() != PaymentType.REFUND)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (successful.compareTo(invoice.getDepositAmount()) >= 0) return false;

        boolean inFlight = lockedPayments.stream().anyMatch(p ->
                p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.PROCESSING);
        if (inFlight && now.isBefore(invoice.getDepositExpiresAt().plus(properties.pendingPaymentGrace()))) return false;

        List<Mission> affected = cancellableMissions(order.getId());
        if (affected == null) return false;
        lockedPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.PROCESSING)
                .forEach(p -> {
                    p.setStatus(PaymentStatus.CANCELLED);
                    p.setFailureReason("Commercial deposit deadline expired without authoritative confirmation");
                });
        invoice.setStatus(InvoiceStatus.CANCELLED);
        cancel(order, affected, OrderCancellationReason.DEPOSIT_PAYMENT_EXPIRED, now);
        return true;
    }

    /** Expire one Mission receivable after its own handover deadline. */
    @Transactional
    public boolean expireMissionPayment(String milestoneId) {
        MissionPaymentMilestone candidate = milestones.findById(milestoneId).orElse(null);
        if (candidate == null || candidate.getPaymentExpiresAt() == null) return false;
        String orderId = candidate.getInvoice().getOrder().getId();
        Order order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getOrderStatus() == OrderStatus.CANCELLED) return false;
        String invoiceId = candidate.getInvoice().getId();
        Instant now = Instant.now(clock);
        if (now.isBefore(candidate.getPaymentExpiresAt())) return false;

        List<Payment> lockedPayments = payments.findAllByInvoiceIdForUpdate(invoiceId);
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElse(null);
        MissionPaymentMilestone milestone = milestones.findByIdForUpdate(milestoneId).orElse(null);
        if (invoice == null || milestone == null || invoice.getStatus() == InvoiceStatus.CANCELLED
                || invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getRemainingAmount() == null || invoice.getRemainingAmount().signum() <= 0
                || milestone.getPaymentExpiresAt() == null || now.isBefore(milestone.getPaymentExpiresAt())
                || (milestone.getStatus() != MissionPaymentMilestoneStatus.UNPAID
                    && milestone.getStatus() != MissionPaymentMilestoneStatus.PENDING))
            return false;
        boolean missionPaymentInFlight = lockedPayments.stream()
                .filter(payment -> payment.getMissionPaymentMilestone() != null
                        && payment.getMissionPaymentMilestone().getId().equals(milestoneId))
                .anyMatch(payment -> payment.getStatus() == PaymentStatus.PENDING
                        || payment.getStatus() == PaymentStatus.PROCESSING);
        if (missionPaymentInFlight || lockedPayments.stream().anyMatch(payment ->
                payment.getMissionPaymentMilestone() != null
                        && payment.getMissionPaymentMilestone().getId().equals(milestoneId)
                        && payment.getStatus() == PaymentStatus.SUCCESS)) return false;

        milestone.setStatus(MissionPaymentMilestoneStatus.EXPIRED);
        return true;
    }

    /** Null means an operational/resource inconsistency that must be handled manually, never force-cancelled. */
    private List<Mission> cancellableMissions(String orderId) {
        List<Mission> result = new ArrayList<>();
        for (String id : missions.findIdsByOrderIdOrderById(orderId).stream().sorted().toList()) {
            Mission mission = missions.findByIdForUpdate(id).orElse(null);
            if (mission == null) return null;
            if (mission.getStatus() == MissionStatus.CANCELLED) continue;
            if (mission.getStatus() != MissionStatus.PLANNING || missions.hasUnsafeCancellationResources(id)) {
                log.error("Refusing commercial timeout for order {}: mission {} is {} or owns active resources",
                        orderId, id, mission.getStatus());
                return null;
            }
            result.add(mission);
        }
        return result;
    }

    private void cancel(Order order, List<Mission> affected, OrderCancellationReason reason, Instant now) {
        // Never overwrite a prior cancellation cause.
        if (order.getCancellationReason() != null || order.getOrderStatus() == OrderStatus.CANCELLED) return;
        order.setCancellationReason(reason);
        order.setCancelledAt(now);
        order.setOrderStatus(OrderStatus.CANCELLED);
        affected.forEach(mission -> {
            mission.setStatus(MissionStatus.CANCELLED);
            mission.setCancellationAt(now);
            mission.setCancelledBy(SYSTEM_ACTOR);
            mission.setCancellationReason(reason.name());
        });
    }
}
