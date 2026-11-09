package com.ondemandmonitoring.finance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_code", columnNames = "payment_code"),
        @UniqueConstraint(name = "uk_payment_transaction_reference", columnNames = "transaction_reference")},
        indexes = {
                @Index(name = "ix_payments_invoice_status", columnList = "invoice_id,status"),
                @Index(name = "ix_payments_invoice_type_status", columnList = "invoice_id,payment_type,status")})
@Getter @Setter
public class Payment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;
    @Column(name = "payment_code", nullable = false)
    private Long paymentCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 30)
    private PaymentType paymentType;
    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;
    @Column(name = "provider_payment_id", length = 120)
    private String providerTransactionId;
    @Column(name = "checkout_url", length = 1000)
    private String paymentUrl;
    /** Exact vnp_CreateDate sent when the payment URL was signed; required by QueryDR. */
    @Column(name = "provider_request_date", length = 14)
    private String providerRequestDate;
    // Nullable only for legacy provider rows; every new VNPAY payment sets this before insert.
    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;
    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(name = "failure_reason", length = 1000)
    private String failureReason;
}
