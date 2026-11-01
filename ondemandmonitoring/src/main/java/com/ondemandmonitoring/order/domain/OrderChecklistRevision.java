package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "order_checklist_revisions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_order_checklist_revision", columnNames = {"order_id", "revision"})
}, indexes = {
    @Index(name = "ix_order_checklist_revision_order", columnList = "order_id,revision")
})
@Getter
@Setter
@NoArgsConstructor
public class OrderChecklistRevision extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "revision", nullable = false, updatable = false)
    private long revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;

    @Column(name = "change_summary", length = 2000, nullable = false, updatable = false)
    private String changeSummary;

    @Column(name = "payload_fingerprint", length = 128, updatable = false)
    private String payloadFingerprint;
}
