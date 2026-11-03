package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus;
import com.ondemandmonitoring.delivery.enums.ResultRevisionScope;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "result_revision_case_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_revision_case_checklist", columnNames = {"case_id", "order_checklist_item_id"}),
        indexes = @Index(name = "ix_result_revision_case_item_status", columnList = "case_id,status"))
@Getter
@Setter
public class ResultRevisionCaseItem extends BaseEntity {
    @ManyToOne(optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private ResultRevisionCase resultRevisionCase;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_checklist_item_id", nullable = false, updatable = false)
    private OrderChecklistItem orderChecklistItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_decision", nullable = false, length = 30)
    private ResultRevisionScope scopeDecision = ResultRevisionScope.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 30)
    private ResultRevisionStrategy strategy = ResultRevisionStrategy.UNDECIDED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ResultRevisionCaseItemStatus status = ResultRevisionCaseItemStatus.NEEDS_TRIAGE;

    @Column(name = "scope_reason", length = 2000)
    private String scopeReason;

    @Column(name = "manager_instructions", length = 2000)
    private String managerInstructions;

    @Column(name = "scope_decided_by", length = 255)
    private String scopeDecidedBy;

    @Column(name = "scope_decided_at")
    private Instant scopeDecidedAt;

    @Column(name = "assigned_rework_mission_id", length = 255)
    private String assignedReworkMissionId;

    @Column(name = "source_mission_id", length = 255, updatable = false)
    private String sourceMissionId;

    @Column(name = "out_of_scope_notification_key")
    private java.util.UUID outOfScopeNotificationKey;

    @Column(name = "out_of_scope_acknowledged_by", length = 255)
    private String outOfScopeAcknowledgedBy;

    @Column(name = "out_of_scope_acknowledged_at")
    private Instant outOfScopeAcknowledgedAt;
}
