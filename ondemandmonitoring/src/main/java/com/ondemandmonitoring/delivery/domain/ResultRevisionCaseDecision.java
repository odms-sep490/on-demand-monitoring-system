package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.ResultRevisionScope;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_revision_case_decisions", indexes =
        @Index(name = "ix_result_revision_decision_item_time", columnList = "case_item_id,created_at"))
@Getter
@Setter
public class ResultRevisionCaseDecision extends BaseEntity {
    @ManyToOne(optional = false)
    @JoinColumn(name = "case_item_id", nullable = false, updatable = false)
    private ResultRevisionCaseItem caseItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_decision", nullable = false, length = 30, updatable = false)
    private ResultRevisionScope scopeDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 30, updatable = false)
    private ResultRevisionStrategy strategy;

    @Column(name = "reason", nullable = false, length = 2000, updatable = false)
    private String reason;

    @Column(name = "manager_instructions", length = 2000, updatable = false)
    private String managerInstructions;

    @Column(name = "decided_by", nullable = false, updatable = false, length = 255)
    private String decidedBy;
}
