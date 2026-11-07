package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "mission_checklist_executions", uniqueConstraints =
        @UniqueConstraint(name = "uk_execution_assignment", columnNames = "mission_checklist_assignment_id"),
        indexes = @Index(name = "ix_execution_mission", columnList = "mission_id"))
@org.hibernate.annotations.Check(constraints = "(execution_status = 'UNABLE_TO_VERIFY' and unable_to_verify_reason is not null and length(trim(unable_to_verify_reason)) > 0 or execution_status <> 'UNABLE_TO_VERIFY' and unable_to_verify_reason is null) and (execution_status in ('COMPLETED','UNABLE_TO_VERIFY') and started_at is not null and completed_at is not null or execution_status = 'IN_PROGRESS' and started_at is not null and completed_at is null or execution_status = 'PENDING' and started_at is null and completed_at is null)")
@Getter
@Setter
public class MissionChecklistExecution extends BaseEntity {
    // Both exact-scope composite FKs share this technical Order key.
    // Apply that migration even after ddl-auto=create; plain JPA joins alone cannot enforce this invariant.
    @Column(name = "mission_id", nullable = false, updatable = false)
    private String missionId;
    @Column(name = "order_checklist_item_id", nullable = false, updatable = false)
    private String orderChecklistItemId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private String orderId;

    // Cutover migration verifies/backfills history and owns the composite same-scope FK.
    @Column(name = "mission_checklist_assignment_id", nullable = false, updatable = false)
    private String missionChecklistAssignmentId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_checklist_assignment_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private MissionChecklistAssignment missionChecklistAssignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_checklist_item_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private OrderChecklistItem orderChecklistItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 30)
    private ChecklistExecutionStatus executionStatus = ChecklistExecutionStatus.PENDING;
    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_status", nullable = false, length = 30)
    private ChecklistAssessmentStatus assessmentStatus = ChecklistAssessmentStatus.NOT_ASSESSED;
    @Column(name = "assessment_note", length = 2000)
    private String assessmentNote;
    @Column(name = "assessed_by", length = 255)
    private String assessedBy;
    @Column(name = "assessed_at")
    private Instant assessedAt;
    @Column(length = 2000)
    private String observation;
    @Column(name = "unable_to_verify_reason", length = 1000)
    private String unableToVerifyReason;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "last_modified_by", length = 255)
    private String lastModifiedBy;
}
