package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.ChecklistAssignmentStatus;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.pricing.enums.ChecklistDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_checklist_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_checklist_source", columnNames = {"order_id", "checklist_revision", "source_checklist_id"}),
        @UniqueConstraint(name = "uk_checklist_item_id_order", columnNames = {"id", "order_id"})},
        indexes = {
                @Index(name = "ix_order_checklist_order", columnList = "order_id,display_order,id"),
                @Index(name = "ix_order_checklist_order_rev", columnList = "order_id,checklist_revision,display_order,id"),
                @Index(name = "ix_order_checklist_pricing_source", columnList = "pricing_status,source_type,created_at"),
                @Index(name = "ix_order_checklist_created_source", columnList = "created_at,source_type")})
@org.hibernate.annotations.Check(constraints = "display_order >= 0 and ((source_type in ('SERVICE_TEMPLATE','CUSTOMER_SELECTED') and source_checklist_id is not null) or (source_type = 'CUSTOMER_CUSTOM' and source_checklist_id is null))")
@Getter
@Setter
@NoArgsConstructor
public class OrderChecklistItem extends BaseEntity {
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "order_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_order_checklist_order"))
        private Order order;
        @Column(name = "checklist_revision", nullable = false, updatable = false)
        private long checklistRevision = 1;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "source_checklist_id", updatable = false, foreignKey = @ForeignKey(name = "fk_order_checklist_source"))
        private ChecklistDefinition sourceChecklist;
        @Column(nullable = false, length = 500, updatable = false)
        private String content;
        /**
         * Guidance as it read when the order was placed; later catalog edits never
         * change an existing order.
         */
        @Column(name = "description_snapshot", length = 4000, updatable = false)
        private String descriptionSnapshot;
        @jakarta.persistence.Convert(converter = com.ondemandmonitoring.checklist.domain.StringListJsonConverter.class)
        @Column(name = "execution_steps_snapshot", columnDefinition = "text", updatable = false)
        private java.util.List<String> executionStepsSnapshot;
        @jakarta.persistence.Convert(converter = com.ondemandmonitoring.checklist.domain.StringListJsonConverter.class)
        @Column(name = "input_requirements_snapshot", columnDefinition = "text", updatable = false)
        private java.util.List<String> inputRequirementsSnapshot;
        @jakarta.persistence.Convert(converter = com.ondemandmonitoring.checklist.domain.StringListJsonConverter.class)
        @Column(name = "expected_outputs_snapshot", columnDefinition = "text", updatable = false)
        private java.util.List<String> expectedOutputsSnapshot;
        @Column(name = "display_order", nullable = false, updatable = false)
        private int displayOrder;
        @Enumerated(EnumType.STRING)
        @Column(name = "source_type", nullable = false, length = 30, updatable = false)
        private OrderChecklistSourceType sourceType;

        @Enumerated(EnumType.STRING)
        @Column(name = "review_status", nullable = false)
        private ChecklistReviewStatus reviewStatus;

        @Column(name = "manager_note", length = 1000)
        private String managerNote;

        @Enumerated(EnumType.STRING)
        @Column(name = "difficulty_snapshot", nullable = false, length = 20, updatable = false, columnDefinition = "varchar(20) default 'BASIC'")
        private ChecklistDifficulty difficultySnapshot = ChecklistDifficulty.BASIC;

        @Column(name = "evidence_policy_version", nullable = false, updatable = false)
        private int evidencePolicyVersion;
        @Column(name = "minimum_evidence_count", nullable = false, updatable = false)
        private int minimumEvidenceCount = 1;

        @Enumerated(EnumType.STRING)
        @Column(name = "required_evidence_type", nullable = false, length = 30, updatable = false)
        private RequiredEvidenceType requiredEvidenceType = RequiredEvidenceType.IMAGE_OR_VIDEO;

        /**
         * Evidence policy V3 only: independent image and video thresholds (both must be
         * met; type IMAGE_AND_VIDEO when both are > 0).
         */
        @Column(name = "minimum_image_count", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int minimumImageCount;
        @Column(name = "minimum_video_count", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int minimumVideoCount;

        /**
         * 0 = legacy snapshot priced by difficulty only; 1 = priced from the media
         * snapshot below.
         */
        @Column(name = "pricing_snapshot_version", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int pricingSnapshotVersion;
        @Column(name = "base_price_snapshot", nullable = false, updatable = false, precision = 19, scale = 0, columnDefinition = "numeric(19,0) default 0")
        private BigDecimal basePriceSnapshot = BigDecimal.ZERO;
        @Column(name = "included_image_count_snapshot", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int includedImageCountSnapshot;
        @Column(name = "included_video_count_snapshot", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int includedVideoCountSnapshot;
        @Column(name = "requested_image_count", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int requestedImageCount;
        @Column(name = "requested_video_count", nullable = false, updatable = false, columnDefinition = "integer default 0")
        private int requestedVideoCount;
        @Column(name = "standard_video_duration_snapshot", updatable = false)
        private Integer standardVideoDurationSnapshot;
        @Column(name = "pricing_profile_snapshot", length = 60, updatable = false)
        private String pricingProfileSnapshot;

        @Column(name = "area_pricing_enabled_snapshot", nullable = false, updatable = false, columnDefinition = "boolean default false")
        private boolean areaPricingEnabledSnapshot;
        @Column(name = "length_pricing_enabled_snapshot", nullable = false, updatable = false, columnDefinition = "boolean default false")
        private boolean lengthPricingEnabledSnapshot;
        @Column(name = "altitude_pricing_enabled_snapshot", nullable = false, updatable = false, columnDefinition = "boolean default false")
        private boolean altitudePricingEnabledSnapshot;
        @Column(name = "included_area_ha_snapshot", nullable = false, updatable = false, precision = 14, scale = 3, columnDefinition = "numeric(14,3) default 0")
        private BigDecimal includedAreaHaSnapshot = BigDecimal.ZERO;
        @Column(name = "included_length_km_snapshot", nullable = false, updatable = false, precision = 14, scale = 3, columnDefinition = "numeric(14,3) default 0")
        private BigDecimal includedLengthKmSnapshot = BigDecimal.ZERO;
        @Column(name = "included_altitude_m_snapshot", nullable = false, updatable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) default 0")
        private BigDecimal includedAltitudeMSnapshot = BigDecimal.ZERO;

        /**
         * True for customer-added rows that require an explicit Manager pricing review.
         */
        @Column(name = "manager_quote_required", nullable = false, updatable = false, columnDefinition = "boolean default false")
        private boolean managerQuoteRequired;
        /**
         * Manager-approved final price for this checklist (equal to system price when
         * approved unchanged).
         */
        @Column(name = "manager_quoted_price", precision = 19, scale = 0)
        private BigDecimal managerQuotedPrice;

        /** Immutable first system calculation captured when Manager pricing starts. */
        @Column(name = "system_calculated_price", precision = 19, scale = 0)
        private BigDecimal systemCalculatedPrice;

        @Column(name = "manager_price_adjustment_reason", length = 1000)
        private String managerPriceAdjustmentReason;

        @Column(name = "manager_approved_amount", precision = 19, scale = 0)
        private BigDecimal managerApprovedAmount;

        @Column(name = "final_amount", precision = 19, scale = 0)
        private BigDecimal finalAmount;

        @Column(name = "price_difference_amount", precision = 19, scale = 0)
        private BigDecimal priceDifferenceAmount;

        @Column(name = "price_difference_percent", precision = 12, scale = 6)
        private BigDecimal priceDifferencePercent;

        @Enumerated(EnumType.STRING)
        @Column(name = "pricing_status", nullable = false, length = 40, columnDefinition = "varchar(40) default 'NEEDS_MANAGER_REVIEW'")
        private ChecklistPricingStatus pricingStatus = ChecklistPricingStatus.NEEDS_MANAGER_REVIEW;

        /**
         * Mission assignment tracking: an item belongs to at most one effective
         * Mission.
         */
        @Enumerated(EnumType.STRING)
        @Column(name = "assignment_status", nullable = false, length = 20, columnDefinition = "varchar(20) default 'UNASSIGNED'")
        private ChecklistAssignmentStatus assignmentStatus = ChecklistAssignmentStatus.UNASSIGNED;
}
