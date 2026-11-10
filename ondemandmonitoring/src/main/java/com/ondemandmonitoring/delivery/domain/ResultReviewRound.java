package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_review_rounds",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_review_round_order_number", columnNames = {"order_id", "round_number"}),
        indexes = @Index(name = "ix_result_review_round_order_number", columnList = "order_id,round_number"))
@Getter @Setter
public class ResultReviewRound extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "delivery_id", nullable = false, updatable = false, length = 255)
    private String deliveryId;

    @Column(name = "mission_id", updatable = false, length = 255)
    private String missionId;

    @Column(name = "round_number", nullable = false, updatable = false)
    private int roundNumber;

    @Column(name = "checklist_revision", nullable = false, updatable = false)
    private long checklistRevision;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by", updatable = false)
    private User publishedBy;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ResultReviewRoundStatus status = ResultReviewRoundStatus.OPEN;

}
