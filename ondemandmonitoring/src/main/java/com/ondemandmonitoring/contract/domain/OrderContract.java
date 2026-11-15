package com.ondemandmonitoring.contract.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.contract.enums.ContractStatus;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@org.hibernate.annotations.Check(
    name = "ck_order_contract_state",
    constraints =
        "quote_version > 0 and status in"
            + " ('AWAITING_SIGNATURE','PENDING_VERIFICATION','CHANGES_REQUESTED','CONFIRMED','CANCELLED') and"
            + " ((status = 'CONFIRMED' and confirmed_at is not null and confirmed_by is not null)"
            + " or (status <> 'CONFIRMED' and confirmed_at is null and confirmed_by is null))")
@Table(
    name = "order_contracts",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_contract_invoice", columnNames = "invoice_id"),
      @UniqueConstraint(name = "uk_contract_quote", columnNames = "quote_id"),
      @UniqueConstraint(name = "uk_contract_number", columnNames = "contract_number")
    })
public class OrderContract extends BaseEntity {
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "invoice_id")
  private Invoice invoice;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "quote_id", nullable = false, updatable = false)
  private Quote quote;

  @Column(name = "quote_version", nullable = false, updatable = false)
  private int quoteVersion;

  @Column(name = "contract_number", nullable = false, updatable = false, length = 80)
  private String contractNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ContractStatus status;

  // Changes on every signed upload, including PENDING -> PENDING replacement, so @Version advances.
  @Column(name="submission_revision",nullable=false,columnDefinition="bigint default 0")
  private long submissionRevision;

  @Embedded private ContractDocument document;

  @Column(name = "issued_by", nullable = false, updatable = false)
  private String issuedBy;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "confirmed_by")
  private String confirmedBy;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;
}
