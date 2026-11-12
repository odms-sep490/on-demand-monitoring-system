package com.ondemandmonitoring.refund.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.enums.PaymentProvider;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentMilestoneService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.domain.Refund;
import com.ondemandmonitoring.refund.domain.RefundTransferAttempt;
import com.ondemandmonitoring.refund.dto.RecordRefundTransferAttemptRequest;
import com.ondemandmonitoring.refund.dto.RequestDepositRefundRequest;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import com.ondemandmonitoring.refund.enums.TransferOutcome;
import com.ondemandmonitoring.refund.repository.RefundReceiptRepository;
import com.ondemandmonitoring.refund.repository.RefundRepository;
import com.ondemandmonitoring.refund.repository.RefundTransferAttemptRepository;
import com.ondemandmonitoring.refund.enums.RefundExecutionChannel;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceRequestTest {
    @Mock private RefundRepository refunds;
    @Mock private RefundTransferAttemptRepository attempts;
    @Mock private RefundReceiptRepository receipts;
    @Mock private OrderRepository orders;
    @Mock private InvoiceRepository invoices;
    @Mock private PaymentRepository payments;
    @Mock private MissionPaymentMilestoneService missionMilestones;
    @Mock private MissionRepository missions;
    @Mock private AuthenticatedUserResolver currentUser;
    @Mock private IMediaObjectStorage storage;
    @Mock private ApplicationEventPublisher events;

    private RefundService service;
    private Order order;
    private Invoice invoice;
    private Payment firstDeposit;
    private Payment secondDeposit;
    private User manager;

    @BeforeEach
    void setUp() {
        service = new RefundService(refunds, attempts, receipts, orders, invoices, payments, missionMilestones,
                missions, currentUser, storage, events);

        User customer = new User();
        customer.setId("customer-1");
        customer.setFullName("Customer One");
        customer.setEmail("customer@example.test");
        manager = new User();
        manager.setId("manager-1");
        manager.setFullName("Manager One");
        when(currentUser.getCurrentUser()).thenReturn(manager);

        order = new Order();
        order.setId("order-1");
        order.setOrderCode("ORD-001");
        order.setTitle("Survey");
        order.setCustomer(customer);
        order.setOrderStatus(OrderStatus.APPROVED);
        order.getFlightCompliance().setPermitRequired(true);
        order.getFlightCompliance().setPermitStatus(OrderPermitStatus.NEED_SUPPORT);
        order.getFlightAreaReview().setLegalReviewStatus(LegalReviewStatus.REJECTED);
        order.getFlightAreaReview().setFlightAreaReviewReason("Permit verification was rejected");

        invoice = new Invoice();
        invoice.setId("invoice-1");
        invoice.setInvoiceNumber("INV-001");
        invoice.setDepositAmount(new BigDecimal("100"));

        firstDeposit = successfulDeposit("payment-1", "70", "txn-1");
        secondDeposit = successfulDeposit("payment-2", "50", "txn-2");
        when(refunds.existsByOrderId("order-1")).thenReturn(false);
        when(orders.findByIdForUpdate("order-1")).thenReturn(java.util.Optional.of(order));
        when(invoices.findByOrderId("order-1")).thenReturn(java.util.Optional.of(invoice));
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(java.util.Optional.of(invoice));
        when(payments.findAllByInvoiceIdForUpdate("invoice-1"))
                .thenReturn(List.of(firstDeposit, secondDeposit));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order-1"))
                .thenReturn(List.of());
        when(refunds.saveAndFlush(any(Refund.class))).thenAnswer(invocation -> {
            Refund saved = invocation.getArgument(0);
            saved.setId("refund-1");
            return saved;
        });
        when(attempts.findAllByRefundIdOrderByAttemptNumberAsc("refund-1")).thenReturn(List.of());
    }

    @Test
    void calculatesFullRefundFromVerifiedDepositsButCapsAtInvoiceDeposit() {
        service.requestDepositRefund("order-1", new RequestDepositRefundRequest("Permit was rejected"));

        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        verify(refunds).saveAndFlush(saved.capture());
        assertEquals(new BigDecimal("100"), saved.getValue().getRefundableAmount());
        assertEquals(List.of(firstDeposit, secondDeposit), saved.getValue().getDepositPayments());
        assertEquals(manager, saved.getValue().getRequestedBy());
        assertEquals("Permit was rejected", saved.getValue().getReason());
    }

    @Test
    void rejectsPendingDepositEvenWhenAnotherDepositHasSucceeded() {
        Payment pending = new Payment();
        pending.setPaymentType(PaymentType.DEPOSIT);
        pending.setStatus(PaymentStatus.PENDING);
        pending.setAmount(BigDecimal.ONE);
        when(payments.findAllByInvoiceIdForUpdate("invoice-1"))
                .thenReturn(List.of(firstDeposit, pending));

        assertThrows(ApiException.class, () -> service.requestDepositRefund("order-1",
                new RequestDepositRefundRequest("Permit was rejected")));
    }

    @Test
    void rejectsUnderpaidDepositPayments() {
        when(payments.findAllByInvoiceIdForUpdate("invoice-1"))
                .thenReturn(List.of(firstDeposit));

        assertThrows(ApiException.class, () -> service.requestDepositRefund("order-1",
                new RequestDepositRefundRequest("Permit was rejected")));
        verify(refunds, never()).saveAndFlush(any(Refund.class));
    }

    @Test
    void rejectsAnOrderWhosePermitCanStillProceedThroughReview() {
        order.getFlightAreaReview().setLegalReviewStatus(LegalReviewStatus.PENDING_PERMIT);

        assertThrows(ApiException.class, () -> service.requestDepositRefund("order-1",
                new RequestDepositRefundRequest("Stop retrying")));
        verify(payments, never()).findAllByInvoiceIdForUpdate("invoice-1");
    }

    @Test
    void rejectsDuplicateRefundRequestsForTheSameOrder() {
        when(refunds.existsByOrderId("order-1")).thenReturn(true);

        assertThrows(ApiException.class, () -> service.requestDepositRefund("order-1",
                new RequestDepositRefundRequest("Duplicate request")));
        verify(invoices, never()).findByOrderId("order-1");
    }

    @Test
    void cannotCompleteARefundWithoutReceiptEvidence() {
        Refund refund = new Refund();
        refund.setId("refund-1");
        refund.setStatus(RefundStatus.PROCESSING);
        refund.setRefundableAmount(new BigDecimal("100"));
        RefundTransferAttempt successful = new RefundTransferAttempt();
        successful.setId("attempt-1");
        successful.setRefund(refund);
        successful.setBeneficiaryBank("Example Bank");
        successful.setBeneficiaryName("Customer One");
        successful.setBeneficiaryAccountNumber("1234567890");
        successful.setBeneficiaryConfirmedWithCustomer(true);
        successful.setAmount(new BigDecimal("100"));
        successful.setTransactionReference("BANK-001");
        successful.setTransferredAt(Instant.parse("2026-10-04T00:00:00Z"));
        successful.setOutcome(TransferOutcome.SUCCESSFUL);
        when(refunds.findByIdForUpdate("refund-1")).thenReturn(java.util.Optional.of(refund));
        when(attempts.findAllByRefundIdOrderByAttemptNumberAsc("refund-1"))
                .thenReturn(List.of(successful));

        assertThrows(ApiException.class, () -> service.confirmRefund("refund-1"));
        assertEquals(RefundStatus.PROCESSING, refund.getStatus());
    }

    @Test
    void requiresCustomerBeneficiaryConfirmationBeforeRecordingTransfer() {
        Refund refund = new Refund();
        refund.setId("refund-1");
        refund.setStatus(RefundStatus.PROCESSING);
        when(refunds.findByIdForUpdate("refund-1")).thenReturn(java.util.Optional.of(refund));
        when(attempts.findByTransactionReference("BANK-001")).thenReturn(java.util.Optional.empty());
        when(attempts.existsByRefundIdAndOutcome("refund-1", TransferOutcome.SUCCESSFUL))
                .thenReturn(false);
        var request = new RecordRefundTransferAttemptRequest(
                RefundExecutionChannel.BANK_TRANSFER,
                "Example Bank", "Customer One", "1234567890", false,
                new BigDecimal("100"), "BANK-001", Instant.parse("2026-10-04T00:00:00Z"),
                null, TransferOutcome.SUCCESSFUL, null);

        assertThrows(ApiException.class, () -> service.recordTransferAttempt("refund-1", request));
        verify(attempts, never()).saveAndFlush(any(RefundTransferAttempt.class));
    }

    private Payment successfulDeposit(String id, String amount, String reference) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setPaymentType(PaymentType.DEPOSIT);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setAmount(new BigDecimal(amount));
        payment.setCurrency("VND");
        payment.setProvider(PaymentProvider.VNPAY);
        payment.setTransactionReference(reference);
        payment.setPaidAt(Instant.parse("2026-10-01T00:00:00Z"));
        return payment;
    }
}
