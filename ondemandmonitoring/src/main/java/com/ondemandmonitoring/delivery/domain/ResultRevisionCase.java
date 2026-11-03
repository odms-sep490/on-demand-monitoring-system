package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus;
import com.ondemandmonitoring.order.domain.Order;
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

import java.time.Instant;

@Entity
@Table(name = "result_revision_cases", indexes = {
        @Index(name = "ix_result_revision_case_order_active", columnList = "order_id,active")
})
@Getter
@Setter
public class ResultRevisionCase extends BaseEntity {
    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "ticket_id", nullable = false, unique = true, updatable = false, length = 255)
    private String ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ResultRevisionCaseStatus status = ResultRevisionCaseStatus.OPEN;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
