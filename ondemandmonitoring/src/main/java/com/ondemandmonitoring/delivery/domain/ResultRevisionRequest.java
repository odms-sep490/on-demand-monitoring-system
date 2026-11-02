package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.Order;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_revision_requests",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_result_revision_request_round", columnNames = "round_id"),
                @UniqueConstraint(name = "uk_result_revision_request_ticket", columnNames = "ticket_id"),
                @UniqueConstraint(name = "uk_result_revision_request_customer_key", columnNames = {"customer_id", "client_request_id"})
        }, indexes = @Index(name = "ix_result_revision_request_order_submitted", columnList = "order_id,submitted_at"))
@Getter @Setter
public class ResultRevisionRequest extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_id", nullable = false, updatable = false)
    private ResultReviewRound round;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "customer_id", nullable = false, updatable = false, length = 255)
    private String customerId;

    @Column(name = "ticket_id", nullable = false, updatable = false, length = 255)
    private String ticketId;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private java.util.UUID clientRequestId;

    @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 128)
    private String payloadFingerprint;

    @Column(name = "general_note", length = 2000, updatable = false)
    private String generalNote;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;
}
