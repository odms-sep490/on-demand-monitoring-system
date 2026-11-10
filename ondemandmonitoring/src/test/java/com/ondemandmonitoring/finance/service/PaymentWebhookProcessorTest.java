package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.gateway.VerifiedWebhook;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.finance.service.impl.PaymentWebhookProcessor;
import com.ondemandmonitoring.order.domain.Order;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentWebhookProcessorTest {
    PaymentRepository payments = mock(PaymentRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    com.ondemandmonitoring.mission.service.IMissionService missions =
            mock(com.ondemandmonitoring.mission.service.IMissionService.class);
    com.ondemandmonitoring.order.repository.OrderRepository orders = mock(com.ondemandmonitoring.order.repository.OrderRepository.class);
    PaymentWebhookProcessor processor = new PaymentWebhookProcessor(payments, invoices, missions, orders);
    com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService delivery = mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
    Payment payment; Invoice invoice;

    @BeforeEach void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "deliveryWorkflow", delivery);
        Order order = new Order(); order.setId("o1");
        Quote quote = new Quote(); quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        invoice = new Invoice(); invoice.setId("i1"); invoice.setOrder(order); invoice.setQuote(quote);
        invoice.setTotalAmount(new BigDecimal("1000")); invoice.setDepositAmount(new BigDecimal("300"));
        invoice.setPaidAmount(BigDecimal.ZERO); invoice.setRemainingAmount(new BigDecimal("1000")); invoice.setStatus(InvoiceStatus.ISSUED);
        payment = new Payment(); payment.setId("p1"); payment.setInvoice(invoice); payment.setPaymentCode(123L);
        payment.setTransactionReference("123");
        payment.setAmount(new BigDecimal("300")); payment.setProvider(PaymentProvider.VNPAY); payment.setStatus(PaymentStatus.PENDING);
        when(payments.findByTransactionReferenceForUpdate("123")).thenReturn(Optional.of(payment));
        when(invoices.findByIdForUpdate("i1")).thenReturn(Optional.of(invoice));
        when(payments.findOrderIdByTransactionReference("123")).thenReturn(Optional.of("o1"));
        when(orders.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
    }

    @Test void wrongAmountDoesNotChangePayment() {
        assertThat(processor.process(webhook(299))).isEqualTo(PaymentWebhookResult.INVALID_AMOUNT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test void successfulDepositUpdatesInvoiceWithoutMutatingMission() {
        when(payments.sumSuccessfulPayments("i1")).thenReturn(new BigDecimal("300"));
        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("300");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test void duplicateSuccessWebhookDoesNotCreditAgain() {
        payment.setStatus(PaymentStatus.SUCCESS);
        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.ALREADY_CONFIRMED);
        verify(invoices, never()).findByIdForUpdate(anyString());
        verify(payments, never()).sumSuccessfulPayments(anyString());
    }

    @Test void depositProcessesUnderOrderPaymentInvoiceLocksAndReplayDoesNotCreditAgain() {
        when(payments.sumSuccessfulPayments("i1")).thenReturn(new BigDecimal("300"));
        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.PROCESSED);
        var locks = inOrder(orders, payments, invoices);
        locks.verify(orders).findByIdForUpdate("o1");
        locks.verify(payments).findByTransactionReferenceForUpdate("123");
        locks.verify(invoices).findByIdForUpdate("i1");
        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.ALREADY_CONFIRMED);
        verify(payments, times(1)).sumSuccessfulPayments("i1");
    }

    @Test void finalPaymentMakesInvoicePaid() {
        invoice.setPaidAmount(new BigDecimal("300")); payment.setAmount(new BigDecimal("700")); payment.setPaymentType(PaymentType.FINAL_PAYMENT);
        when(payments.sumSuccessfulPayments("i1")).thenReturn(new BigDecimal("1000"));
        processor.process(webhook(700));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getRemainingAmount()).isEqualByComparingTo("0");
        verify(missions).transitionToPendingConfirmation("o1");
        verify(delivery).completeAcceptedRevisionCase("o1");
    }

    @Test void partialFinalPaymentDoesNotOpenMissionConfirmation() {
        invoice.setPaidAmount(new BigDecimal("300")); payment.setAmount(new BigDecimal("200"));
        payment.setPaymentType(PaymentType.FINAL_PAYMENT);
        when(payments.sumSuccessfulPayments("i1")).thenReturn(new BigDecimal("500"));
        processor.process(webhook(200));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        verifyNoInteractions(missions);
    }

    @Test void missionActivationFailureDoesNotUndoVerifiedPayment() {
        invoice.setPaidAmount(new BigDecimal("300")); payment.setAmount(new BigDecimal("700"));
        payment.setPaymentType(PaymentType.FINAL_PAYMENT);
        when(payments.sumSuccessfulPayments("i1")).thenReturn(new BigDecimal("1000"));
        doThrow(new RuntimeException("mission store unavailable"))
                .when(missions).transitionToPendingConfirmation("o1");

        assertThat(processor.process(webhook(700))).isEqualTo(PaymentWebhookResult.PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test void fullFinalPaymentSchedulesMissionActivationAfterSettlementCommit() {
        invoice.setPaidAmount(new BigDecimal("300")); payment.setAmount(new BigDecimal("700"));
        payment.setPaymentType(PaymentType.FINAL_PAYMENT);
        when(payments.sumSuccessfulPayments("i1")).thenReturn(new BigDecimal("1000"));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            processor.process(webhook(700));
            verifyNoInteractions(missions);
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations().forEach(
                            org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(missions).transitionToPendingConfirmation("o1");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test void duplicateFullFinalPaymentRetriesActivationWithoutCreditingAgain() {
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaymentType(PaymentType.FINAL_PAYMENT);
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setRemainingAmount(BigDecimal.ZERO);

        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.ALREADY_CONFIRMED);
        verify(missions).transitionToPendingConfirmation("o1");
        verify(payments, never()).sumSuccessfulPayments(anyString());
    }

    @Test void successfulLateIpnIsRecordedForReviewWithoutReopeningCancelledOrder() {
        invoice.getOrder().setOrderStatus(com.ondemandmonitoring.order.enums.OrderStatus.CANCELLED);
        payment.setStatus(PaymentStatus.CANCELLED);
        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.LATE_PAYMENT_RECORDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
        assertThat(payment.getFailureReason()).contains("manual refund/reconciliation");
        assertThat(invoice.getPaidAmount()).isZero();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.getOrder().getOrderStatus()).isEqualTo(com.ondemandmonitoring.order.enums.OrderStatus.CANCELLED);
    }

    @Test void unknownVnPayOrderIsReportedWithoutMutation() {
        when(payments.findByTransactionReferenceForUpdate("123")).thenReturn(Optional.empty());
        assertThat(processor.process(webhook(300))).isEqualTo(PaymentWebhookResult.ORDER_NOT_FOUND);
        verifyNoInteractions(invoices);
    }

    private VerifiedWebhook webhook(long amount) {
        return new VerifiedWebhook("123", BigDecimal.valueOf(amount), "VND", "tx1", null,
                "00", "00", true);
    }
}
