package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "result_review_round_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_review_round_item_checklist", columnNames = {"round_id", "order_checklist_item_id"}),
        indexes = @Index(name = "ix_result_review_round_item_order", columnList = "round_id,display_order"))
@Getter @Setter
public class ResultReviewRoundItem extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_id", nullable = false, updatable = false)
    private ResultReviewRound round;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_checklist_item_id", nullable = false, updatable = false)
    private OrderChecklistItem orderChecklistItem;

    @Column(name = "content_snapshot", nullable = false, length = 500, updatable = false)
    private String contentSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_policy_snapshot", columnDefinition = "jsonb", updatable = false)
    private JsonNode evidencePolicySnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guidance_snapshot", columnDefinition = "jsonb", updatable = false)
    private JsonNode guidanceSnapshot;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    @Column(name = "changed_in_round", nullable = false, updatable = false)
    private boolean changedInRound;

    @Column(name = "execution_id", length = 255, updatable = false)
    private String executionId;

    @Column(name = "mission_id", length = 255, updatable = false)
    private String missionId;

    @Column(name = "mission_result_id", length = 255, updatable = false)
    private String missionResultId;

    @Column(name = "result_version", length = 255, updatable = false)
    private String resultVersion;
}
