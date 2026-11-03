package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Planning state and revision history for an order's executable mission plan. */
@Entity
@Table(name = "order_execution_plans")
@Getter
@Setter
@NoArgsConstructor
public class OrderExecutionPlan extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExecutionPlanStatus status = ExecutionPlanStatus.DRAFT;

    @PositiveOrZero
    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by", length = 255)
    private String finalizedBy;
}
