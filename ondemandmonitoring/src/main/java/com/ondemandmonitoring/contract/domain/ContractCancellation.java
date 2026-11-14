package com.ondemandmonitoring.contract.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.order.domain.Order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@org.hibernate.annotations.Check(
    name = "ck_contract_cancellation_state",
    constraints =
        "deposit_paid >= 0 and ((status = 'REVIEW_REQUIRED' and responsibility = 'UNRESOLVED' and"
            + " decided_at is null and decided_by is null) or (status <> 'REVIEW_REQUIRED' and"
            + " responsibility <> 'UNRESOLVED' and decided_at is not null and decided_by is not"
            + " null and basis is not null and length(trim(basis)) >= 10)) and (status <>"
            + " 'REFUND_REQUESTED' or (responsibility = 'SYSTEM' and deposit_paid > 0)) and (status"
            + " <> 'DEPOSIT_RETAINED' or (responsibility = 'CUSTOMER' and deposit_paid > 0)) and"
            + " (status <> 'NO_PAYMENT_DUE' or deposit_paid = 0)")
@Entity
@Getter
@Setter
@Table(
    name = "contract_cancellations",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_contract_cancellation_order", columnNames = "order_id"),
      @UniqueConstraint(
          name = "uk_contract_cancellation_request",
          columnNames = "client_request_id")
    })
public class ContractCancellation extends BaseEntity {
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "contract_id", nullable = false, unique = true)
  private OrderContract contract;

  @Column(name = "client_request_id", nullable = false, updatable = false, length = 36)
  private String clientRequestId;

  @Column(nullable = false, updatable = false, length = 2000)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(name = "requested_responsibility", nullable = false, updatable = false, length = 30)
  private CancellationResponsibility requestedResponsibility;

  @Column(name = "requested_basis", updatable = false, length = 2000)
  private String requestedBasis;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private CancellationResponsibility responsibility;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ContractCancellationStatus status;

  @Column(length = 2000)
  private String basis;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "requested_by", nullable = false, updatable = false)
  private String requestedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decided_by")
  private String decidedBy;

  @Column(name = "deposit_paid", nullable = false, precision = 19, scale = 0)
  private BigDecimal depositPaid;

  @Column(name = "refund_id")
  private String refundId;
}
