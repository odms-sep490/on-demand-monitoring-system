package com.ondemandmonitoring.pricing.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.pricing.enums.ChecklistPricingAuditAction;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Immutable audit entry written for every customer-added checklist when a quote is approved. */
@Entity
@Table(name = "checklist_pricing_audits", indexes = {
        @Index(name = "ix_checklist_pricing_audit_quote", columnList = "quote_id"),
        @Index(name = "ix_checklist_pricing_audit_item", columnList = "order_checklist_item_id"),
        @Index(name = "ix_checklist_pricing_audit_reviewed", columnList = "reviewed_at")})
@Getter
@Setter
public class ChecklistPricingAudit extends BaseEntity {
    @Column(name = "order_id", nullable = false, length = 36, updatable = false)
    private String orderId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, updatable = false)
    private Quote quote;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_checklist_item_id", nullable = false, updatable = false)
    private OrderChecklistItem orderChecklistItem;
    @Column(name = "system_amount", nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal systemAmount;
    @Column(name = "approved_amount", nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal approvedAmount;
    @Column(name = "final_amount", nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal finalAmount;
    @Column(name = "difference_amount", nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal differenceAmount;
    @Column(name = "difference_percent", precision = 12, scale = 6, updatable = false)
    private BigDecimal differencePercent;
    @Column(name = "adjustment_reason", length = 1000, updatable = false)
    private String adjustmentReason;
    @Column(name = "pricing_policy_id", length = 36, updatable = false)
    private String pricingPolicyId;
    @Column(name = "pricing_policy_version", updatable = false)
    private Integer pricingPolicyVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewed_by", nullable = false, updatable = false)
    private User reviewedBy;
    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private Instant reviewedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private ChecklistPricingAuditAction action;
}
