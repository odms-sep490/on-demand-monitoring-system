package com.ondemandmonitoring.refund.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.refund.enums.TransferOutcome;
import com.ondemandmonitoring.refund.enums.RefundExecutionChannel;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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
@Table(name = "deposit_refund_transfer_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_attempt_number", columnNames = {"refund_id", "attempt_number"}),
        @UniqueConstraint(name = "uk_refund_bank_reference", columnNames = "transaction_reference"),
        @UniqueConstraint(name = "uk_deposit_refund_attempt_id_refund", columnNames = {"id", "refund_id"})
}, indexes = @Index(name = "ix_refund_attempt_refund", columnList = "refund_id,attempt_number"))
@Getter
@Setter
@NoArgsConstructor
public class RefundTransferAttempt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false)
    private Refund refund;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_channel", nullable = false, length = 30)
    private RefundExecutionChannel executionChannel = RefundExecutionChannel.BANK_TRANSFER;

    @Column(name = "beneficiary_bank", length = 120)
    private String beneficiaryBank;

    @Column(name = "beneficiary_name", length = 120)
    private String beneficiaryName;

    @Column(name = "beneficiary_account_number", length = 80)
    private String beneficiaryAccountNumber;

    @Column(name = "beneficiary_confirmed_with_customer", nullable = false)
    private boolean beneficiaryConfirmedWithCustomer;

    @Column(name = "transfer_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "transaction_reference", nullable = false, length = 120)
    private String transactionReference;

    @Column(name = "transferred_at", nullable = false)
    private Instant transferredAt;

    @Column(name = "transfer_note", length = 1000)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private TransferOutcome outcome;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by_id", nullable = false)
    private User recordedBy;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.PERSIST, orphanRemoval = false)
    private List<RefundReceipt> receipts = new ArrayList<>();
}
