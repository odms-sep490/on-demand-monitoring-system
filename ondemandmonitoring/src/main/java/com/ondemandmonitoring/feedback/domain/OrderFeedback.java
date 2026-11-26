package com.ondemandmonitoring.feedback.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Customer satisfaction rating submitted once per order after the result is accepted. */
@Entity
@Table(name = "order_feedbacks", uniqueConstraints = @UniqueConstraint(name = "uk_order_feedbacks_order", columnNames = "order_id"))
@Getter @Setter
public class OrderFeedback extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private User customer;

    /** Overall rating, 1 to 5 stars. */
    @Column(nullable = false)
    private int rating;

    @Column(name = "quality_rating") private Integer qualityRating;
    @Column(name = "punctuality_rating") private Integer punctualityRating;
    @Column(name = "communication_rating") private Integer communicationRating;

    @Column(length = 2000)
    private String comment;
}
