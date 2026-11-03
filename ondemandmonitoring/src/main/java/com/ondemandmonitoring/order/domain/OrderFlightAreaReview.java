package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Manager's legal and requested-flight-area evaluation for an order. */
@Entity
@Table(name = "order_flight_area_reviews")
@Getter
@Setter
@NoArgsConstructor
public class OrderFlightAreaReview extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_review_status", nullable = false, length = 30)
    private LegalReviewStatus legalReviewStatus = LegalReviewStatus.NOT_REVIEWED;

    @Column(name = "flight_area_review_reason", length = 500)
    private String flightAreaReviewReason;

    @Column(name = "flight_area_review_note", columnDefinition = "TEXT")
    private String flightAreaReviewNote;

    @Column(name = "requested_scope_feasible")
    private Boolean requestedScopeFeasible;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_area_reviewed_by")
    private User flightAreaReviewedBy;

    @Column(name = "flight_area_reviewed_at")
    private java.time.Instant flightAreaReviewedAt;
}
