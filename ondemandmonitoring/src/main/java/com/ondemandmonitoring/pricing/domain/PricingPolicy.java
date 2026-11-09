package com.ondemandmonitoring.pricing.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.pricing.enums.PricingPolicyStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pricing_policies", uniqueConstraints = @UniqueConstraint(name = "uk_pricing_policy_version", columnNames = "policy_version"))
@Getter @Setter
public class PricingPolicy extends BaseEntity {
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "policy_version", nullable = false) private int policyVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PricingPolicyStatus status;
    @Column(nullable = false, length = 3) private String currency = "VND";
    @Column(name = "effective_from") private Instant effectiveFrom;
    @Column(name = "effective_to") private Instant effectiveTo;
    @Column(name = "manager_max_discount_percent", nullable = false, precision = 7, scale = 4) private BigDecimal managerMaxDiscountPercent;
    @Column(name = "manager_max_markup_percent", nullable = false, precision = 7, scale = 4) private BigDecimal managerMaxMarkupPercent;
    @Column(name = "high_risk_discount_percent", nullable = false, precision = 7, scale = 4) private BigDecimal highRiskDiscountPercent;
    @Column(name = "minimum_price_ratio", nullable = false, precision = 7, scale = 4) private BigDecimal minimumPriceRatio;
    @Column(name = "minimum_price", nullable = false, precision = 19, scale = 0) private BigDecimal minimumPrice;
    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("priority ASC, id ASC") private List<PricingRule> rules = new ArrayList<>();

    public void replaceRules(List<PricingRule> replacements) {
        rules.clear();
        replacements.forEach(rule -> { rule.setPolicy(this); rules.add(rule); });
    }
}
