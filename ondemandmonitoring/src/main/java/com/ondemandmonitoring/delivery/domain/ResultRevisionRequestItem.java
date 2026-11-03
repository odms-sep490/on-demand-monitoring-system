package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.ResultRevisionReasonCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_revision_request_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_revision_request_item_round_item", columnNames = {"request_id", "round_item_id"}),
        indexes = @Index(name = "ix_result_revision_request_item_order", columnList = "request_id,display_order"))
@Getter @Setter
public class ResultRevisionRequestItem extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private ResultRevisionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_item_id", nullable = false, updatable = false)
    private ResultReviewRoundItem roundItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "case_item_id", updatable = false)
    private ResultRevisionCaseItem caseItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private ResultRevisionReasonCode reasonCode;

    @Column(name = "note", nullable = false, length = 2000, updatable = false)
    private String note;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;
}
