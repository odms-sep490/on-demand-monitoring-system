package com.ondemandmonitoring.refund.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "deposit_refunds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_deposit_refunds_order", columnNames = "order_id")
}, indexes = {
        @Index(name = "ix_deposit_refunds_status_requested", columnList = "status,requested_at"),
        @Index(name = "ix_deposit_refunds_customer", columnList = "customer_id,requested_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Refund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "deposit_refund_payments",
            joinColumns = @JoinColumn(name = "refund_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "payment_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(name = "uk_deposit_refund_payment", columnNames = {"refund_id", "payment_id"}))
    private List<Payment> depositPayments = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private User requestedBy;

    @Column(name = "request_reason", nullable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processing_admin_id")
    private User processingAdmin;

    @Column(name = "processing_at")
    private Instant processingAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by_id")
    private User completedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by_id")
    private User rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 2000)
    private String cancellationReason;

    @Column(name = "refundable_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal refundableAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "refund", cascade = CascadeType.PERSIST, orphanRemoval = false)
    @OrderBy("attemptNumber ASC")
    private List<RefundTransferAttempt> attempts = new ArrayList<>();
}
