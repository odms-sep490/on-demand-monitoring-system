package com.ondemandmonitoring.contract.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.IContractCancellationService;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.dto.RequestDepositRefundRequest;
import com.ondemandmonitoring.refund.service.RefundService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContractCancellationServiceImpl implements IContractCancellationService {
  private final OrderRepository orders;
  private final InvoiceRepository invoices;
  private final OrderContractRepository contracts;
  private final ContractCancellationRepository cancellations;
  private final PaymentRepository payments;
  private final MissionRepository missions;
  private final RefundService refunds;
  private final AuthenticatedUserResolver users;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public ContractCancellationResponse get(String orderId) {
    Order order =
        orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    var actor = users.getCurrentUser();
    String role =
        actor.getRole() == null || actor.getRole().getCode() == null
            ? ""
            : actor.getRole().getCode().name();
    if (!Set.of("MANAGER", "ADMIN").contains(role)
        && !order.getCustomer().getId().equals(actor.getId()))
      throw new ApiException(ErrorCode.ACCESS_DENIED);
    return cancellations.findByOrderId(orderId).map(this::response).orElse(null);
  }

  @Override
  @Transactional
  @PreAuthorize("hasRole('MANAGER')")
  public ContractCancellationResponse request(String orderId, ContractCancellationRequest request) {
    requireManager();
    var context = lock(orderId);
    var existing = cancellations.findByOrderId(orderId);
    if (existing.isPresent()) {
      var row = existing.get();
      if (row.getClientRequestId().equals(request.clientRequestId().toString())
          && row.getReason().equals(request.reason().trim())
          && row.getRequestedResponsibility() == request.responsibility()
          && Objects.equals(row.getRequestedBasis(), trim(request.basis()))) return response(row);
      throw conflict();
    }
    OrderContract contract =
        contracts
            .findByInvoiceId(context.invoice().getId())
            .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND));
    if (context.order().getOrderStatus() != OrderStatus.APPROVED
        || contract.getStatus() == ContractStatus.CONFIRMED
        || contract.getStatus() == ContractStatus.CANCELLED
        || context.invoice().isContractReady()
        || context.invoice().getStatus() == InvoiceStatus.CANCELLED
        || context.invoice().getStatus() == InvoiceStatus.REFUNDED
        || !Objects.equals(contract.getVersion(), request.expectedVersion())) throw conflict();
    BigDecimal total = verifiedDeposit(context.invoice());
    validateDecision(
        request.responsibility(),
        request.basis(),
        request.customerLiabilityAcknowledged(),
        total,
        context.invoice());
    ContractCancellation row = new ContractCancellation();
    row.setOrder(context.order());
    row.setContract(contract);
    row.setClientRequestId(request.clientRequestId().toString());
    row.setReason(request.reason().trim());
    row.setRequestedResponsibility(request.responsibility());
    row.setRequestedBasis(trim(request.basis()));
    row.setResponsibility(CancellationResponsibility.UNRESOLVED);
    row.setStatus(ContractCancellationStatus.REVIEW_REQUIRED);
    row.setBasis(trim(request.basis()));
    row.setRequestedAt(Instant.now(clock));
    row.setRequestedBy(users.getCurrentUserId());
    row.setDepositPaid(total);
    contract.setStatus(ContractStatus.CANCELLED);
    context.order().setOrderStatus(OrderStatus.CANCELLED);
    context.order().setCancelledAt(Instant.now(clock));
    context.order().setCancellationReason(OrderCancellationReason.CONTRACT_REVIEW_REQUIRED);
    cancellations.saveAndFlush(row);
    settle(row, context.invoice(), request.responsibility(), request.basis());
    return response(cancellations.saveAndFlush(row));
  }

  @Override
  @Transactional
  @PreAuthorize("hasRole('MANAGER')")
  public ContractCancellationResponse decide(String orderId, ContractCancellationDecision request) {
    requireManager();
    var context = lock(orderId);
    var row =
        cancellations
            .findByOrderId(orderId)
            .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    if (row.getStatus() != ContractCancellationStatus.REVIEW_REQUIRED) {
      if (row.getResponsibility() == request.responsibility()
          && Objects.equals(row.getBasis(), trim(request.basis()))) return response(row);
      throw conflict();
    }
    if (context.invoice().getStatus() == InvoiceStatus.CANCELLED
        || context.invoice().getStatus() == InvoiceStatus.REFUNDED
        || !Objects.equals(row.getVersion(), request.expectedVersion())
        || request.responsibility() == CancellationResponsibility.UNRESOLVED
        || context.order().getOrderStatus() != OrderStatus.CANCELLED
        || row.getContract().getStatus() != ContractStatus.CANCELLED) throw conflict();
    BigDecimal total = verifiedDeposit(context.invoice());
    validateDecision(
        request.responsibility(),
        request.basis(),
        request.customerLiabilityAcknowledged(),
        total,
        context.invoice());
    row.setDepositPaid(total);
    settle(row, context.invoice(), request.responsibility(), request.basis());
    return response(cancellations.saveAndFlush(row));
  }

  private record Context(Order order, Invoice invoice) {}

  private Context lock(String orderId) {
    Order order =
        orders
            .findByIdForUpdate(orderId)
            .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    // This workflow is strictly pre-execution; use existing operational cancellation once Missions
    // exist.
    if (!missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).isEmpty())
      throw new ApiException(ErrorCode.CONTRACT_CANCELLATION_MISSION_EXISTS);
    Invoice current =
        invoices
            .findByOrderId(orderId)
            .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    Invoice invoice =
        invoices
            .findByIdForUpdate(current.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    if (!invoice.isContractRequired() || invoice.getContractConfirmedAt() != null) throw conflict();
    return new Context(order, invoice);
  }

  private BigDecimal verifiedDeposit(Invoice invoice) {
    var all = payments.findAllByInvoiceIdForUpdate(invoice.getId());
    if (all.stream()
        .anyMatch(
            p ->
                p.getStatus() == PaymentStatus.PENDING
                    || p.getStatus() == PaymentStatus.PROCESSING))
      throw new ApiException(ErrorCode.CONTRACT_CANCELLATION_PENDING_PAYMENT);
    var successful = all.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCESS).toList();
    if (successful.stream()
        .anyMatch(
            p ->
                p.getPaymentType() != PaymentType.DEPOSIT
                    || p.getPaidAt() == null
                    || !"VND".equals(p.getCurrency())
                    || p.getAmount() == null
                    || p.getAmount().signum() <= 0)) throw conflict();
    BigDecimal total =
        successful.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (invoice.getPaidAmount() == null || total.compareTo(invoice.getPaidAmount()) != 0)
      throw new ApiException(ErrorCode.CONTRACT_FINANCIAL_REVIEW_REQUIRED);
    return total;
  }

  private void validateDecision(
      CancellationResponsibility party,
      String basis,
      boolean acknowledged,
      BigDecimal paid,
      Invoice invoice) {
    if (party == null) throw conflict();
    if (party != CancellationResponsibility.UNRESOLVED
        && (trim(basis) == null || basis.trim().length() < 10))
      throw new ApiException(ErrorCode.INVALID_REQUEST);
    if (party == CancellationResponsibility.CUSTOMER && !acknowledged)
      throw new ApiException(ErrorCode.CONTRACT_LIABILITY_BASIS_REQUIRED);
    if (party == CancellationResponsibility.CUSTOMER
        && paid.compareTo(invoice.getDepositAmount()) > 0)
      throw new ApiException(ErrorCode.CONTRACT_FINANCIAL_REVIEW_REQUIRED);
  }

  private void settle(
      ContractCancellation row, Invoice invoice, CancellationResponsibility party, String basis) {
    row.setResponsibility(party);
    row.setBasis(trim(basis));
    if (party == CancellationResponsibility.UNRESOLVED) return;
    row.setDecidedAt(Instant.now(clock));
    row.setDecidedBy(users.getCurrentUserId());
    row.getOrder()
        .setCancellationReason(
            party == CancellationResponsibility.SYSTEM
                ? OrderCancellationReason.CONTRACT_SYSTEM_RESPONSIBILITY
                : OrderCancellationReason.CONTRACT_CUSTOMER_RESPONSIBILITY);
    if (row.getDepositPaid().signum() == 0) {
      row.setStatus(ContractCancellationStatus.NO_PAYMENT_DUE);
      invoice.setStatus(InvoiceStatus.CANCELLED);
    } else if (party == CancellationResponsibility.SYSTEM) {
      row.setStatus(ContractCancellationStatus.REFUND_REQUESTED);
      cancellations.saveAndFlush(row);
      String reason = "Contract cancellation " + row.getId() + ": " + row.getReason();
      var refund =
          refunds.requestDepositRefund(
              row.getOrder().getId(),
              new RequestDepositRefundRequest(
                  reason.substring(0, Math.min(2000, reason.length()))));
      row.setRefundId(refund.id());
    } else {
      row.setStatus(ContractCancellationStatus.DEPOSIT_RETAINED);
      invoice.setStatus(InvoiceStatus.CANCELLED);
    }
  }

  private ContractCancellationResponse response(ContractCancellation row) {
    return new ContractCancellationResponse(
        row.getId(),
        row.getVersion() == null ? 0 : row.getVersion(),
        row.getStatus(),
        row.getResponsibility(),
        row.getReason(),
        row.getBasis(),
        row.getDepositPaid(),
        row.getRefundId(),
        row.getRequestedAt(),
        row.getRequestedBy(),
        row.getDecidedAt(),
        row.getDecidedBy());
  }

  private void requireManager() {
    if (users.getCurrentUser().getRole() == null
        || users.getCurrentUser().getRole().getCode()
            != com.ondemandmonitoring.role.enumeration.RoleCode.MANAGER)
      throw new ApiException(ErrorCode.ACCESS_DENIED);
  }

  private String trim(String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }

  private ApiException conflict() {
    return new ApiException(ErrorCode.CONTRACT_STATE_CONFLICT);
  }
}
