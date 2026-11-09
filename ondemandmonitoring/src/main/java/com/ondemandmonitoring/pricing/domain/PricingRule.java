package com.ondemandmonitoring.pricing.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.pricing.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pricing_rules", indexes = @Index(name = "ix_pricing_rules_policy_category", columnList = "policy_id,category"))
@Getter @Setter
public class PricingRule extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "policy_id", nullable = false) private PricingPolicy policy;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PricingCategory category;
    @Enumerated(EnumType.STRING) @Column(name = "calculation_type", nullable = false, length = 30) private PricingCalculationType calculationType;
    @Column(name = "rule_key", nullable = false, length = 80) private String ruleKey;
    @Column(nullable = false, length = 200) private String description;
    @Column(name = "min_value", precision = 19, scale = 4) private BigDecimal minValue;
    @Column(name = "max_value", precision = 19, scale = 4) private BigDecimal maxValue;
    @Column(name = "fixed_amount", precision = 19, scale = 0) private BigDecimal fixedAmount;
    @Column(name = "base_amount", precision = 19, scale = 0) private BigDecimal baseAmount;
    @Column(name = "percentage", precision = 12, scale = 6) private BigDecimal percentage;
    @Column(name = "unit_size", precision = 19, scale = 4) private BigDecimal unitSize;
    @Column(name = "multiplier", precision = 12, scale = 6) private BigDecimal multiplier;
    /** MEDIA rules only: profile key shared by a checklist's pricing rules (e.g. STANDARD, CUSTOM). */
    @Column(name = "pricing_profile", length = 60) private String pricingProfile;
    /** Optional catalog-checklist target. Null means a profile/global fallback rule. */
    @Column(name = "checklist_definition_id", length = 36) private String checklistDefinitionId;
    @Enumerated(EnumType.STRING) @Column(name = "media_component", length = 30) private MediaPricingComponent mediaComponent;
    /** Default allowance for profiles that have no checklist definition (custom checklists). */
    @Column(name = "included_quantity") private Integer includedQuantity;
    @Enumerated(EnumType.STRING) @Column(name = "rounding_mode", length = 20) private PricingRounding roundingMode;
    @Column(nullable = false) private int priority;
    @Column(nullable = false) private boolean enabled = true;
}
