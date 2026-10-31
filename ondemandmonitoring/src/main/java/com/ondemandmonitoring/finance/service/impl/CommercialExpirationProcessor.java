package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
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
                || order.getDepositExpiresAt() == null) return false;
        Instant now = Instant.now(clock);
        if (now.isBefore(order.getDepositExpiresAt())) return false;

        // Keep lock order identical to PaymentWebhookProcessor: order, payments, invoice, missions.
        List<Payment> lockedPayments = payments.findAllByInvoiceIdForUpdate(invoiceId);
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() == InvoiceStatus.CANCELLED
                || invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.REFUNDED)
            return false;
        BigDecimal successful = lockedPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS && p.getPaymentType() != PaymentType.REFUND)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (successful.compareTo(invoice.getDepositAmount()) >= 0) return false;

        boolean inFlight = lockedPayments.stream().anyMatch(p ->
                p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.PROCESSING);
        if (inFlight && now.isBefore(order.getDepositExpiresAt().plus(properties.pendingPaymentGrace()))) return false;

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

    /** Null means an operational/resource inconsistency that must be handled manually, never force-cancelled. */
    private List<Mission> cancellableMissions(String orderId) {
        List<Mission> result = new java.util.ArrayList<>();
        for (String id : missions.findIdsByOrderIdOrderById(orderId).stream().sorted().toList()) {
            Mission mission = missions.findByIdForUpdate(id).orElse(null);
            if (mission == null) return null;
            if (mission.getStatus() == MissionStatus.CANCELLED) continue;
            if ((mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.WAITING_DEPOSIT)
                    || missions.hasUnsafeCancellationResources(id)) {
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
            mission.setResourcePrepareStatus(com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus.CANCELLED);
            mission.setCancellationAt(now);
            mission.setCancelledBy(SYSTEM_ACTOR);
            mission.setCancellationReason(reason.name());
        });
    }
}
