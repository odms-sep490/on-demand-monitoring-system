package com.ondemandmonitoring.finance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.pricing.enums.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "quotes", uniqueConstraints = @UniqueConstraint(name = "uk_quote_order_version", columnNames = {"order_id", "quote_version"}),
        indexes = {
                @Index(name = "ix_quotes_order_status", columnList = "order_id,status"),
                @Index(name = "ix_quotes_status_updated", columnList = "status,updated_at"),
                @Index(name = "ix_quotes_expiration_status", columnList = "expires_at,status"),
                @Index(name = "ix_quotes_created", columnList = "created_at")})
@Getter @Setter
public class Quote extends BaseEntity {
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist_guidance_snapshot", columnDefinition = "jsonb")
    private com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot checklistGuidanceSnapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "quote_version", nullable = false)
    private int quoteVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private QuoteStatus status;

    @Column(name = "package_price", nullable = false, precision = 19, scale = 0)
    private BigDecimal packagePrice;
    @Column(name = "additional_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal additionalAmount;
    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal discountAmount;
    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal adjustmentAmount;
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalAmount;
    @Column(name = "pricing_policy_id", length = 36)
    private String pricingPolicyId;
    @Column(name = "pricing_policy_version")
    private int pricingPolicyVersion;
    @Column(name = "system_calculated_amount", precision = 19, scale = 0)
    private BigDecimal systemCalculatedAmount;
    @Column(name = "manager_adjustment_amount", precision = 19, scale = 0)
    private BigDecimal managerAdjustmentAmount;
    @Enumerated(EnumType.STRING) @Column(name = "adjustment_type", length = 30)
    private PricingAdjustmentType adjustmentType;
    @Column(name = "adjustment_reason", length = 2000)
    private String adjustmentReason;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "adjustment_requested_by")
    private User adjustmentRequestedBy;
    @Column(name = "adjustment_requested_at") private Instant adjustmentRequestedAt;
    @Enumerated(EnumType.STRING) @Column(name = "pricing_approval_status", length = 40)
    private PricingApprovalStatus pricingApprovalStatus = PricingApprovalStatus.NOT_REQUIRED;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pricing_approved_by")
    private User pricingApprovedBy;
    @Column(name = "pricing_approved_at") private Instant pricingApprovedAt;
    @Column(name = "pricing_approval_note", length = 2000) private String pricingApprovalNote;
    @Column(name = "pricing_revision") private long pricingRevision;
    @Column(name = "checklist_revision", nullable = false) private long checklistRevision = 1;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "pricing_risk_flags", columnDefinition = "jsonb")
    private List<PricingRiskFlag> pricingRiskFlags = new ArrayList<>();
    @Column(name = "manager_note", length = 2000)
    private String managerNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    /** Fixed when the quote is issued. Null is the explicit legacy/no-auto-expiry policy. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<QuoteItem> items = new ArrayList<>();

    public void replaceItems(List<QuoteItem> replacements) {
        items.clear();
        replacements.forEach(item -> { item.setQuote(this); items.add(item); });
    }
}
