package com.ondemandmonitoring.finance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.order.domain.Order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "invoices", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_number", columnNames = "invoice_number"),
        @UniqueConstraint(name = "uk_invoice_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_invoice_quote", columnNames = "quote_id")},
        indexes = {
                @Index(name = "ix_invoices_status", columnList = "status"),
                @Index(name = "ix_invoices_deposit_status", columnList = "deposit_status"),
                @Index(name = "ix_invoices_deposit_expiration", columnList = "deposit_expires_at,status")
        })
@Getter @Setter
public class Invoice extends BaseEntity {
    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalAmount;
    @Column(name = "deposit_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal depositAmount;
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal paidAmount;
    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal remainingAmount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InvoiceStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_status", nullable = false, length = 30)
    private DepositStatus depositStatus = DepositStatus.WAITING_DEPOSIT;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name="contract_required", nullable=false, columnDefinition="boolean default false")
    private boolean contractRequired;
    @Column(name="contract_confirmed_at")
    private Instant contractConfirmedAt;
    public boolean isContractReady() { return !contractRequired || contractConfirmedAt != null; }

    /** New invoices start this deadline only after contract verification. Legacy deadlines stay unchanged. */
    @Column(name = "deposit_expires_at")
    private Instant depositExpiresAt;

    @PrePersist
    @PreUpdate
    private void synchronizeDepositStatus() {
        refreshDepositStatus();
    }

    /** Amounts remain the source of truth; this is also safe for legacy rows. */
    public DepositStatus effectiveDepositStatus() {
        return paidAmount != null && depositAmount != null && paidAmount.compareTo(depositAmount) >= 0
                ? DepositStatus.DEPOSIT_PAID
                : DepositStatus.WAITING_DEPOSIT;
    }

    public void refreshDepositStatus() {
        depositStatus = effectiveDepositStatus();
    }
}
