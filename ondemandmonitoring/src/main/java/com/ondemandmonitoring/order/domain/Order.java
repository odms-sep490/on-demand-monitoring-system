package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.enums.OrderDeliveryMethod;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.OrderCancellationReason;
import com.ondemandmonitoring.order.enums.OrderFlightPurpose;
import com.ondemandmonitoring.order.enums.OrderUsagePurpose;
import com.ondemandmonitoring.order.enums.OrderWeatherFallback;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "orders", indexes = {
        @jakarta.persistence.Index(name = "ix_orders_status_created", columnList = "order_status,created_at"),
        @jakarta.persistence.Index(name = "ix_orders_created", columnList = "created_at")})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order extends BaseEntity {

    @JsonIgnore
    @Builder.Default
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private OrderExecutionPlan executionPlan = new OrderExecutionPlan();


    @Column(name = "order_code", unique = true, length = 40)
    private String orderCode;

    @Column(name = "checklist_snapshot_at")
    private Instant checklistSnapshotAt;

    /** Current immutable checklist snapshot revision; existing snapshots start at revision 1. */
    @jakarta.persistence.Column(name = "checklist_revision", nullable = false)
    private long checklistRevision = 0;

    /** Pricing policy frozen when the request is submitted. Legacy orders may leave these fields null. */
    @Column(name = "pricing_policy_id", length = 36)
    private String pricingPolicyId;

    @Column(name = "pricing_policy_version")
    private Integer pricingPolicyVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User customer;

    @Column(name = "title", nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_purpose", length = 30)
    private OrderUsagePurpose usagePurpose;

    /** Purpose of the flight (Decree 288/2025/ND-CP, Article 4.3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "flight_purpose", length = 30)
    private OrderFlightPurpose flightPurpose;

    @Column(name = "altitude_m")
    private Double altitudeM;

    @Column(name = "estimated_length_m")
    private Double estimatedLengthM;

    @Column(name = "site_contact_name", length = 120)
    private String siteContactName;

    @Column(name = "site_contact_phone", length = 30)
    private String siteContactPhone;

    @Column(name = "access_notes", columnDefinition = "TEXT")
    private String accessNotes;

    @JsonIgnore
    @Builder.Default
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private OrderFlightCompliance flightCompliance = new OrderFlightCompliance();

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "point", columnDefinition = "geometry(Point,4326)")
    private Point point;

    @Column(name = "target_area", columnDefinition = "geometry(Polygon,4326)")
    private Polygon targetArea;

    @Column(name = "preferred_date_from")
    private LocalDate preferredDateFrom;

    @Column(name = "preferred_date_to")
    private LocalDate preferredDateTo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preferred_time_id", nullable = false)
    private PreferredTime preferredTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type", length = 20)
    private OrderRecurrenceType recurrenceType;

    /** Total number of flights when the order repeats (2-52); null when it does not repeat. */
    @Column(name = "recurrence_occurrences")
    private Integer recurrenceOccurrences;

    @Enumerated(EnumType.STRING)
    @Column(name = "weather_fallback", length = 30)
    private OrderWeatherFallback weatherFallback;

    /** Latest date by which the customer needs the result. */
    @Column(name = "result_deadline")
    private LocalDate resultDeadline;

    /** Requested result formats (photo, video, PDF report, orthomosaic, 3D model). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_formats", columnDefinition = "jsonb")
    private List<OrderResultFormat> resultFormats;

    /** How the customer receives the result (download, email, API). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivery_methods", columnDefinition = "jsonb")
    private List<OrderDeliveryMethod> deliveryMethods;

    /** Days the result data is kept after handover. */
    @Column(name = "data_retention_days", nullable = false)
    private Integer dataRetentionDays;

    /** Customer result access expires at this instant; set only on first originals handover. */
    @Column(name = "result_expires_at")
    private Instant resultExpiresAt;

    /** When the customer accepted the terms and the data-security commitment. */
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 40)
    private OrderCancellationReason cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** Customer-facing section that the manager explicitly asked to be corrected. */
    @Column(name = "requested_update_scope", length = 40)
    private String requestedUpdateScope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_by")
    private User reviewBy;

    @Column(name = "review_at")
    private Instant reviewAt;

    @JsonIgnore
    @Builder.Default
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private OrderFlightAreaReview flightAreaReview = new OrderFlightAreaReview();


    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderDeliverable> deliverables = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void connectFlightProfiles() {
        if (flightCompliance != null) flightCompliance.setOrder(this);
        if (flightAreaReview != null) flightAreaReview.setOrder(this);
        if (executionPlan != null) executionPlan.setOrder(this);
    }

}
