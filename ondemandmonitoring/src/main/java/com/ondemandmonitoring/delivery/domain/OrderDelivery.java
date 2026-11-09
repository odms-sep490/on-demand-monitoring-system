package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Stores the delivery-specific state of an order after mission execution.
 *
 * <p>This aggregate records review decisions and release timestamps without
 * overloading the broader order or mission lifecycle.</p>
 */
@Entity
@Table(name = "order_deliveries", uniqueConstraints = @UniqueConstraint(name = "uk_order_delivery_order", columnNames = "order_id"))
@Getter @Setter
public class OrderDelivery extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DeliveryStatus status;

    @Column(name = "delivery_notes", length = 2000)
    private String deliveryNotes;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "preview_released_by")
    private User previewReleasedBy;
    @Column(name = "preview_released_at") private Instant previewReleasedAt;
    @Column(name = "customer_accepted_at") private Instant customerAcceptedAt;
    @Column(name = "revision_requested_at") private Instant revisionRequestedAt;
    @Column(name = "revision_reason", length = 2000) private String revisionReason;
    @Column(name = "revision_count", nullable = false) private int revisionCount;
    @Column(name = "current_review_round_id", length = 255)
    private String currentReviewRoundId;
    @Column(name = "last_checklist_publish_request_id")
    private java.util.UUID lastChecklistPublishRequestId;
    @Column(name = "last_checklist_publish_fingerprint", length = 128)
    private String lastChecklistPublishFingerprint;
    @Column(name = "final_payment_confirmed_at") private Instant finalPaymentConfirmedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "originals_released_by")
    private User originalsReleasedBy;
    @Column(name = "originals_released_at") private Instant originalsReleasedAt;
}
