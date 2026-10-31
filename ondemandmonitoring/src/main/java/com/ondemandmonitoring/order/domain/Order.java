package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.dto.OrderAuthorizationDocument;
import com.ondemandmonitoring.order.dto.OrderPermitDocument;
import com.ondemandmonitoring.order.enums.OrderDeliveryMethod;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.OrderCancellationReason;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderFlightPurpose;
import com.ondemandmonitoring.order.enums.OrderUsagePurpose;
import com.ondemandmonitoring.order.enums.OrderWeatherFallback;
import com.ondemandmonitoring.order.enums.MonitoringRelationship;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
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
import jakarta.persistence.Table;

import java.math.BigDecimal;
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
        @jakarta.persistence.Index(name = "ix_orders_deposit_expiration", columnList = "deposit_expires_at,order_status"),
        @jakarta.persistence.Index(name = "ix_orders_created", columnList = "created_at")})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order extends BaseEntity {

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_plan_status", nullable = false, length = 30)
    private ExecutionPlanStatus executionPlanStatus = ExecutionPlanStatus.DRAFT;

    @Builder.Default
    @jakarta.validation.constraints.PositiveOrZero
    @Column(name = "execution_plan_revision", nullable = false)
    private long executionPlanRevision = 0;

    @Column(name = "execution_plan_finalized_at")
    private Instant executionPlanFinalizedAt;

    @Column(name = "execution_plan_finalized_by", length = 255)
    private String executionPlanFinalizedBy;

    @Column(name = "execution_plan_amended_at")
    private Instant executionPlanAmendedAt;

    @Column(name = "execution_plan_amended_by", length = 255)
    private String executionPlanAmendedBy;

    @Column(name = "execution_plan_amendment_reason", length = 1000)
    private String executionPlanAmendmentReason;

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

    /** Authoritative system calculation at the latest create/update recalculation; never trusted from the client. */
    @Column(name = "system_calculated_amount", precision = 19, scale = 0)
    private BigDecimal systemCalculatedAmount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User customer;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "service_base_price_snapshot", precision = 19, scale = 0)
    private BigDecimal serviceBasePriceSnapshot;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "permit_status", length = 20)
    private OrderPermitStatus permitStatus;

    @Column(name = "permit_number", length = 80)
    private String permitNumber;

    /** Last day the customer's permit is valid; must cover the preferred flight window. */
    @Column(name = "permit_expires_on")
    private LocalDate permitExpiresOn;

    /** Permit files the customer attached (PDF or image). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permit_documents", columnDefinition = "jsonb")
    private List<OrderPermitDocument> permitDocuments;

    @Enumerated(EnumType.STRING)
    @Column(name = "monitoring_relationship", length = 40)
    private MonitoringRelationship monitoringRelationship;

    @Column(name = "monitoring_authorization_confirmed", nullable = false)
    private Boolean monitoringAuthorizationConfirmed = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monitoring_authorization_documents", columnDefinition = "jsonb")
    private List<OrderAuthorizationDocument> monitoringAuthorizationDocuments;

    @Column(name = "permit_required")
    private Boolean permitRequired;

    @Column(name = "permit_zone_name", length = 120)
    private String permitZoneName;

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

    /** Commercial deadline assigned once at quote acceptance; configuration changes never move it. */
    @Column(name = "deposit_expires_at")
    private Instant depositExpiresAt;

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

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "legal_review_status", length = 30)
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
    private Instant flightAreaReviewedAt;


    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderDeliverable> deliverables = new ArrayList<>();
}
