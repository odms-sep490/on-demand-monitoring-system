package com.ondemandmonitoring.refund.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "deposit_refund_receipts", indexes = {
        @Index(name = "ix_refund_receipts_attempt", columnList = "attempt_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RefundReceipt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private RefundTransferAttempt attempt;

    @Column(name = "storage_bucket", nullable = false, length = 255)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false, length = 1000, unique = true)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;
}
