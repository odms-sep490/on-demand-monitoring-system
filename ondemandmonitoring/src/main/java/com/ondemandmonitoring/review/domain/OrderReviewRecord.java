package com.ondemandmonitoring.review.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.review.enums.CriterionResult;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.enums.ReviewCriterionType;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per (order, criterion). MANUAL rows hold the manager's answer. AUTO rows
 * are written when the order is approved so the system verdict at that moment is
 * kept as evidence even if the order data changes later.
 */
@Entity
@Table(name = "order_review_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_order_review_criterion", columnNames = {"order_id", "criterion_code"}))
@Getter
@Setter
@NoArgsConstructor
public class OrderReviewRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_order_review_order"))
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "criterion_code", nullable = false, updatable = false, length = 40)
    private ReviewCriterionCode code;

    @Enumerated(EnumType.STRING)
    @Column(name = "criterion_type", nullable = false, length = 10)
    private ReviewCriterionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 10)
    private CriterionResult result;

    /** System explanation for AUTO rows. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** Manager remark for MANUAL rows. */
    @Column(name = "note", length = 1000)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by", foreignKey = @ForeignKey(name = "fk_order_review_decided_by"))
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
