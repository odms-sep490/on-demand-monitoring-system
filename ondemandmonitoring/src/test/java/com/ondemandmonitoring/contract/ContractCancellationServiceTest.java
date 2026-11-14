package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.impl.ContractCancellationServiceImpl;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.dto.RefundResponse;
import com.ondemandmonitoring.refund.service.RefundService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ContractCancellationServiceTest {
  OrderRepository orders = mock(OrderRepository.class);
  InvoiceRepository invoices = mock(InvoiceRepository.class);
  OrderContractRepository contracts = mock(OrderContractRepository.class);
  ContractCancellationRepository cases = mock(ContractCancellationRepository.class);
  PaymentRepository payments = mock(PaymentRepository.class);
  MissionRepository missions = mock(MissionRepository.class);
  RefundService refunds = mock(RefundService.class);
  AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
  Instant now = Instant.parse("2026-11-14T03:00:00Z");
  ContractCancellationServiceImpl service =
      new ContractCancellationServiceImpl(
          orders,
          invoices,
          contracts,
          cases,
          payments,
          missions,
          refunds,
          users,
          Clock.fixed(now, ZoneOffset.UTC));
  Order order;
  Invoice invoice;
  OrderContract contract;
  Payment payment;
  ContractCancellation stored;
  UUID command = UUID.randomUUID();

  @BeforeEach
  void setup() {
    var manager = OrderContractServiceTest.user("manager", RoleCode.MANAGER);
    var customer = OrderContractServiceTest.user("customer", RoleCode.CUSTOMER);
    when(users.getCurrentUser()).thenReturn(manager);
    when(users.getCurrentUserId()).thenReturn("manager");
    order = new Order();
    order.setId("o");
    order.setCustomer(customer);
    order.setOrderStatus(OrderStatus.APPROVED);
    invoice = new Invoice();
    invoice.setId("i");
    invoice.setOrder(order);
    invoice.setContractRequired(true);
    invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
    invoice.setDepositAmount(new BigDecimal("300"));
    invoice.setPaidAmount(new BigDecimal("300"));
    contract = new OrderContract();
    contract.setId("c");
    contract.setVersion(2L);
    contract.setInvoice(invoice);
    contract.setStatus(ContractStatus.PENDING_VERIFICATION);
    payment = new Payment();
    payment.setId("p");
    payment.setInvoice(invoice);
    payment.setPaymentType(PaymentType.DEPOSIT);
    payment.setStatus(PaymentStatus.SUCCESS);
    payment.setPaidAt(now);
    payment.setAmount(new BigDecimal("300"));
    payment.setCurrency("VND");
    when(orders.findById("o")).thenReturn(Optional.of(order));
    when(orders.findByIdForUpdate("o")).thenReturn(Optional.of(order));
    when(invoices.findByOrderId("o")).thenReturn(Optional.of(invoice));
    when(invoices.findByIdForUpdate("i")).thenReturn(Optional.of(invoice));
    when(contracts.findByInvoiceId("i")).thenReturn(Optional.of(contract));
    when(payments.findAllByInvoiceIdForUpdate("i")).thenReturn(List.of(payment));
    when(cases.findByOrderId("o")).thenAnswer(x -> Optional.ofNullable(stored));
    when(cases.saveAndFlush(any()))
        .thenAnswer(
            x -> {
              stored = x.getArgument(0);
              if (stored.getId() == null) {
                stored.setId("case");
                stored.setVersion(0L);
              } else stored.setVersion(stored.getVersion() + 1);
              return stored;
            });
    var refund = mock(RefundResponse.class);
    when(refund.id()).thenReturn("refund");
    when(refunds.requestDepositRefund(eq("o"), any())).thenReturn(refund);
  }

  ContractCancellationRequest request(CancellationResponsibility party) {
    return new ContractCancellationRequest(
        command,
        2,
        party,
        "The parties cannot proceed",
        party == CancellationResponsibility.UNRESOLVED
            ? null
            : "Contract clause and recorded agreement",
        party == CancellationResponsibility.CUSTOMER);
  }

  @Test
  void systemResponsibilityUsesExistingRefundWorkflowWithoutPretendingMoneyWasTransferred() {
    var result = service.request("o", request(CancellationResponsibility.SYSTEM));
    assertThat(result.status()).isEqualTo(ContractCancellationStatus.REFUND_REQUESTED);
    assertThat(result.refundId()).isEqualTo("refund");
    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getCancellationReason())
        .isEqualTo(OrderCancellationReason.CONTRACT_SYSTEM_RESPONSIBILITY);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    assertThat(invoice.getPaidAmount()).isEqualByComparingTo("300");
    verify(refunds).requestDepositRefund(eq("o"), any());
  }

  @Test
  void customerRetentionRequiresExplicitDecisionAndKeepsRealPaymentHistory() {
    var result = service.request("o", request(CancellationResponsibility.CUSTOMER));
    assertThat(result.status()).isEqualTo(ContractCancellationStatus.DEPOSIT_RETAINED);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    assertThat(invoice.getPaidAmount()).isEqualByComparingTo("300");
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    verifyNoInteractions(refunds);
  }

  @Test
  void unresolvedResponsibilityNeverAutomaticallyRefundsOrRetains() {
    var result = service.request("o", request(CancellationResponsibility.UNRESOLVED));
    assertThat(result.status()).isEqualTo(ContractCancellationStatus.REVIEW_REQUIRED);
    assertThat(result.decidedAt()).isNull();
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    verifyNoInteractions(refunds);
  }

  @Test
  void unresolvedCaseCanLaterSendTheExistingRefundRequest() {
    var pending = service.request("o", request(CancellationResponsibility.UNRESOLVED));
    var result =
        service.decide(
            "o",
            new ContractCancellationDecision(
                pending.version(),
                CancellationResponsibility.SYSTEM,
                "Provider cannot deliver agreed scope",
                false));
    assertThat(result.status()).isEqualTo(ContractCancellationStatus.REFUND_REQUESTED);
    assertThat(result.decidedBy()).isEqualTo("manager");
    assertThat(stored.getRequestedResponsibility())
        .isEqualTo(CancellationResponsibility.UNRESOLVED);
    verify(refunds).requestDepositRefund(eq("o"), any());
  }

  @Test
  void replayNeverCreatesAnotherRefund() {
    var request = request(CancellationResponsibility.SYSTEM);
    service.request("o", request);
    service.request("o", request);
    verify(refunds, times(1)).requestDepositRefund(eq("o"), any());
  }

  @Test
  void retainedDepositCancellationReplayIsAlsoIdempotent() {
    var request = request(CancellationResponsibility.CUSTOMER);
    service.request("o", request);
    assertThat(service.request("o", request).status())
        .isEqualTo(ContractCancellationStatus.DEPOSIT_RETAINED);
    verifyNoInteractions(refunds);
  }

  @Test
  void pendingProviderPaymentsBlockCancellationBeforeAnyMutation() {
    payment.setStatus(PaymentStatus.PROCESSING);
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.SYSTEM)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_CANCELLATION_PENDING_PAYMENT);
    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    verify(cases, never()).saveAndFlush(any());
    verifyNoInteractions(refunds);
  }

  @Test
  void amountMismatchRequiresFinancialReview() {
    invoice.setPaidAmount(new BigDecimal("301"));
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.CUSTOMER)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_FINANCIAL_REVIEW_REQUIRED);
    verifyNoInteractions(refunds);
  }

  @Test
  void customerOverpaymentCannotBeAutomaticallyRetained() {
    invoice.setPaidAmount(new BigDecimal("400"));
    payment.setAmount(new BigDecimal("400"));
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.CUSTOMER)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_FINANCIAL_REVIEW_REQUIRED);
  }

  @Test
  void missingAcknowledgementCannotForfeitDeposit() {
    var r = request(CancellationResponsibility.CUSTOMER);
    assertThatThrownBy(
            () ->
                service.request(
                    "o",
                    new ContractCancellationRequest(
                        r.clientRequestId(), 2, r.responsibility(), r.reason(), r.basis(), false)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_LIABILITY_BASIS_REQUIRED);
    verify(cases, never()).saveAndFlush(any());
  }

  @Test
  void staleVersionDoesNotChangeContractOrMoney() {
    contract.setVersion(3L);
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.SYSTEM)))
        .isInstanceOf(ApiException.class);
    assertThat(contract.getStatus()).isEqualTo(ContractStatus.PENDING_VERIFICATION);
    verifyNoInteractions(refunds);
  }

  @Test
  void staleCaseDecisionIsRejected() {
    var pending = service.request("o", request(CancellationResponsibility.UNRESOLVED));
    assertThatThrownBy(
            () ->
                service.decide(
                    "o",
                    new ContractCancellationDecision(
                        pending.version() + 1,
                        CancellationResponsibility.SYSTEM,
                        "Provider cannot deliver agreed scope",
                        false)))
        .isInstanceOf(ApiException.class);
    verifyNoInteractions(refunds);
  }

  @Test
  void scopeStopsBeforeAnyMissionPlanning() {
    when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o"))
        .thenReturn(List.of(new Mission()));
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.SYSTEM)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_CANCELLATION_MISSION_EXISTS);
  }

  @Test
  void noPaymentCancelsWithoutInventingARefund() {
    invoice.setPaidAmount(BigDecimal.ZERO);
    when(payments.findAllByInvoiceIdForUpdate("i")).thenReturn(List.of());
    assertThat(service.request("o", request(CancellationResponsibility.SYSTEM)).status())
        .isEqualTo(ContractCancellationStatus.NO_PAYMENT_DUE);
    verifyNoInteractions(refunds);
  }

  @Test
  void customerCannotCreateTheManagerDecision() {
    when(users.getCurrentUser()).thenReturn(order.getCustomer());
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.CUSTOMER)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  void anotherCustomerCannotReadTheCase() {
    when(users.getCurrentUser())
        .thenReturn(OrderContractServiceTest.user("other", RoleCode.CUSTOMER));
    assertThatThrownBy(() -> service.get("o"))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  void confirmedContractCannotUsePreExecutionCancellation() {
    contract.setStatus(ContractStatus.CONFIRMED);
    invoice.setContractConfirmedAt(now);
    assertThatThrownBy(() -> service.request("o", request(CancellationResponsibility.SYSTEM)))
        .isInstanceOf(ApiException.class);
    verifyNoInteractions(refunds);
  }
}
