package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Planning reference only. The foundation migration owns composite FKs and active uniqueness. */
@Entity
@Table(name = "mission_checklist_assignments", uniqueConstraints =
        @UniqueConstraint(name = "uk_assignment_identity", columnNames =
                {"id", "mission_id", "order_id", "order_checklist_item_id"}),
        indexes = {
            @Index(name = "ix_assignment_mission_order", columnList = "mission_id,display_order,id"),
            @Index(name = "ix_assignment_coverage", columnList = "order_id,order_checklist_item_id")
        })
@org.hibernate.annotations.Check(constraints = "display_order >= 0 and ((removed_at is null and removed_by is null and removal_reason is null) or (removed_at is not null and removed_by is not null and removed_at >= assigned_at))")
@Getter
@Setter
public class MissionChecklistAssignment extends BaseEntity {
    @Column(name = "mission_id", nullable = false, updatable = false)
    private String missionId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private String orderId;
    @Column(name = "order_checklist_item_id", nullable = false, updatable = false)
    private String orderChecklistItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_checklist_item_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private OrderChecklistItem orderChecklistItem;

    @PositiveOrZero
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "assigned_by", length = 255, updatable = false)
    private String assignedBy;
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;
    @Column(name = "removed_by", length = 255)
    private String removedBy;
    @Column(name = "removed_at")
    private Instant removedAt;
    @Column(name = "removal_reason", length = 1000)
    private String removalReason;
}
