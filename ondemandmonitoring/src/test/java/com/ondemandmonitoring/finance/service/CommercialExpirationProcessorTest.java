package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationProcessor;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommercialExpirationProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    OrderRepository orders = mock(OrderRepository.class);
    QuoteRepository quotes = mock(QuoteRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    PaymentProperties properties = new PaymentProperties(new BigDecimal("0.30"), Duration.ofMinutes(30), 100);
    CommercialExpirationProcessor processor = new CommercialExpirationProcessor(orders, quotes, invoices, payments,
            missions, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    Order order;
    Quote quote;
    Invoice invoice;
    Mission mission;

    @BeforeEach void setup() {
        order = new Order(); order.setId("o1"); order.setOrderStatus(OrderStatus.APPROVED);
        quote = new Quote(); quote.setId("q1"); quote.setOrder(order); quote.setStatus(QuoteStatus.APPROVED);
        quote.setExpiresAt(NOW);
        invoice = new Invoice(); invoice.setId("i1"); invoice.setOrder(order); invoice.setQuote(quote);
        invoice.setStatus(InvoiceStatus.ISSUED); invoice.setDepositAmount(new BigDecimal("300"));
        mission = new Mission(); mission.setId("m1"); mission.setOrder(order); mission.setStatus(MissionStatus.PLANNING);
        when(orders.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(quotes.findById("q1")).thenReturn(Optional.of(quote));
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));
        when(invoices.findById("i1")).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate("i1")).thenReturn(Optional.of(invoice));
        when(missions.findIdsByOrderIdOrderById("o1")).thenReturn(List.of("m1"));
        when(missions.findByIdForUpdate("m1")).thenReturn(Optional.of(mission));
        when(payments.findAllByInvoiceIdForUpdate("i1")).thenReturn(new ArrayList<>());
    }

    @Test void quotationExpiresAtTheExactBoundaryAndCancelsOnlyOnce() {
        assertThat(processor.expireQuote("q1")).isTrue();
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.QUOTATION_EXPIRED);
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.CANCELLED);
        assertThat(processor.expireQuote("q1")).isFalse();
    }

    @Test void acceptedQuoteIsIgnoredByQuotationExpiration() {
        quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        assertThat(processor.expireQuote("q1")).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test void unpaidDepositExpiresAndCancelsPendingAttemptAfterGrace() {
        order.setDepositExpiresAt(NOW.minus(Duration.ofMinutes(31)));
        Payment pending = payment(PaymentStatus.PENDING, new BigDecimal("300"));
        when(payments.findAllByInvoiceIdForUpdate("i1")).thenReturn(List.of(pending));
        assertThat(processor.expireDeposit("i1")).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.DEPOSIT_PAYMENT_EXPIRED);
    }

    @Test void inFlightPaymentGetsBoundedReconciliationGrace() {
        order.setDepositExpiresAt(NOW.minus(Duration.ofMinutes(10)));
        Payment pending = payment(PaymentStatus.PROCESSING, new BigDecimal("300"));
        when(payments.findAllByInvoiceIdForUpdate("i1")).thenReturn(List.of(pending));
        assertThat(processor.expireDeposit("i1")).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test void confirmedDepositAlwaysWinsOverExpiration() {
        order.setDepositExpiresAt(NOW.minus(Duration.ofHours(2)));
        when(payments.findAllByInvoiceIdForUpdate("i1"))
                .thenReturn(List.of(payment(PaymentStatus.SUCCESS, new BigDecimal("300"))));
        assertThat(processor.expireDeposit("i1")).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test void executingMissionPreventsAutomaticCancellation() {
        mission.setStatus(MissionStatus.IN_FLIGHT);
        assertThat(processor.expireQuote("q1")).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test void legacyNullDeadlineIsExplicitlyIgnored() {
        quote.setExpiresAt(null);
        order.setDepositExpiresAt(null);
        assertThat(processor.expireQuote("q1")).isFalse();
        assertThat(processor.expireDeposit("i1")).isFalse();
    }

    private Payment payment(PaymentStatus status, BigDecimal amount) {
        Payment payment = new Payment(); payment.setInvoice(invoice); payment.setPaymentType(PaymentType.DEPOSIT);
        payment.setStatus(status); payment.setAmount(amount); return payment;
    }
}
