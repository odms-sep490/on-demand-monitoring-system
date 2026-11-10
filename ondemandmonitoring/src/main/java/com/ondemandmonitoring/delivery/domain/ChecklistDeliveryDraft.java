package com.ondemandmonitoring.delivery.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "checklist_delivery_drafts")
@Getter
@Setter
public class ChecklistDeliveryDraft extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "mission_id", updatable = false, length = 255)
    private String missionId;

    @Column(name = "case_id", length = 255)
    private String caseId;

    @Column(name = "base_review_round_id", length = 255)
    private String baseReviewRoundId;

    @Column(name = "expected_delivery_version")
    private Long expectedDeliveryVersion;

    @Column(name = "notes", length = 2000)
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist_operations", nullable = false, columnDefinition = "jsonb")
    private JsonNode checklistOperations;

    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;
}
