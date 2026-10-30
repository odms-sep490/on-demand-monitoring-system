package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.pricing.domain.*;
import com.ondemandmonitoring.pricing.dto.*;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleBasedPricingEngine implements PricingEngine {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(0);
    private final PricingPolicyRepository policies;
    /** Retained as a constructor dependency for binary/test compatibility; assessments no longer affect pricing. */
    private final CustomWorkAssessmentRepository assessments;
    private final ChecklistPriceCalculator checklistPrices;
    private final OrderLevelPriceCalculator orderLevelPrices;

    @org.springframework.beans.factory.annotation.Autowired
    public RuleBasedPricingEngine(PricingPolicyRepository policies, CustomWorkAssessmentRepository assessments,
            ChecklistPriceCalculator checklistPrices, OrderLevelPriceCalculator orderLevelPrices) {
        this.policies = policies;
        this.assessments = assessments;
        this.checklistPrices = checklistPrices;
        this.orderLevelPrices = orderLevelPrices;
    }

    /** Test/backward-compatible constructor; production injects the shared order-level calculator above. */
    public RuleBasedPricingEngine(PricingPolicyRepository policies, CustomWorkAssessmentRepository assessments,
            ChecklistPriceCalculator checklistPrices) {
        this(policies, assessments, checklistPrices, new OrderLevelPriceCalculator());
    }

    @Override
    @Transactional(readOnly = true)
    public PricingBreakdown calculate(Order order, List<OrderChecklistItem> checklistItems) {
        PricingPolicy policy = resolvePolicy(order);
        List<PricingBreakdownItem> result = new ArrayList<>();
        // Checklists are the price. Only orders created before that (a stored package snapshot, or version-0 checklist
        // snapshots) keep a PACKAGE line; a new order has none.
        boolean legacyOrder = order.getServiceBasePriceSnapshot() != null
                || checklistItems.stream().anyMatch(i -> i.getPricingSnapshotVersion() == 0);
        BigDecimal base = money(!legacyOrder ? BigDecimal.ZERO : order.getServiceBasePriceSnapshot() != null
                ? order.getServiceBasePriceSnapshot() : order.getService().getBasePrice());
        if (legacyOrder) add(result, PricingCategory.PACKAGE, order.getService().getName(), BigDecimal.ONE, base, base);

        // Legacy snapshots (version 0) keep the single order-level custom charge; new snapshots price each custom item.
        boolean legacyCustom = checklistItems.stream().anyMatch(i -> i.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM
                && i.getPricingSnapshotVersion() == 0
                && i.getReviewStatus() != ChecklistReviewStatus.REJECTED && i.getReviewStatus() != ChecklistReviewStatus.DUPLICATE);
        boolean customWork = checklistItems.stream().anyMatch(i -> i.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM
                && i.getReviewStatus() != ChecklistReviewStatus.REJECTED && i.getReviewStatus() != ChecklistReviewStatus.DUPLICATE);
        boolean assessmentComplete = true;
        if (legacyCustom) keyed(policy, PricingCategory.RESOURCE, "CUSTOM_BASE").ifPresent(r -> addFixed(result, r, BigDecimal.ONE));

        BigDecimal distanceKm = decimal(order.getEstimatedLengthM()).divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
        BigDecimal areaHa = polygonHectares(order);
        BigDecimal altitudeM = decimal(order.getAltitudeM());
        boolean legacyDimensions = checklistItems.stream().allMatch(i -> i.getPricingSnapshotVersion() == 0);
        if (legacyDimensions) {
            BigDecimal legacyDistance = distanceKm;
            ranged(policy, PricingCategory.DISTANCE, legacyDistance).ifPresent(rule -> applyRule(result, rule, legacyDistance, base));
            BigDecimal legacyArea = areaHa;
            ranged(policy, PricingCategory.AREA, legacyArea).ifPresent(rule -> applyRule(result, rule, legacyArea, base));
        }

        // Work done on every visit is billed once per visit; permits, terrain and discounts apply to the order as a whole.
        // Orders priced before this rule (a package snapshot or version-0 checklists) keep their single-visit price.
        int occurrences = legacyOrder || legacyDimensions ? 1 : PricingOccurrences.of(order);
        int visitStart = result.size();
        List<String> pendingQuotes = new ArrayList<>();
        for (OrderChecklistItem item : checklistItems) {
            if (item.getReviewStatus() == ChecklistReviewStatus.REJECTED || item.getReviewStatus() == ChecklistReviewStatus.DUPLICATE) continue;
            if (item.getPricingSnapshotVersion() == 0) {
                String key = Optional.ofNullable(item.getDifficultySnapshot()).orElse(ChecklistDifficulty.BASIC).name();
                keyed(policy, PricingCategory.CHECKLIST, key).ifPresent(rule -> addFixed(result, rule, BigDecimal.ONE));
                continue;
            }
            // Priced only from the frozen snapshot plus the policy: later catalog edits cannot change this order.
            boolean customItem = item.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM;
            String label = shorten(item.getContent());
            ChecklistPriceBreakdown priced;
            {
                ChecklistPriceCalculator.PricingDimensions dimensions = new ChecklistPriceCalculator.PricingDimensions(
                        item.isAreaPricingEnabledSnapshot(), item.isLengthPricingEnabledSnapshot(),
                        item.isAltitudePricingEnabledSnapshot(), item.getIncludedAreaHaSnapshot(),
                        item.getIncludedLengthKmSnapshot(), item.getIncludedAltitudeMSnapshot());
                ChecklistPriceCalculator.RequestContext itemContext = new ChecklistPriceCalculator.RequestContext(
                        areaHa, distanceKm, altitudeM);
                priced = checklistPrices.price(policy, new ChecklistPriceCalculator.Input(
                        item.getSourceChecklist() == null ? null : item.getSourceChecklist().getId(), item.getContent(),
                        item.getDifficultySnapshot(), customItem, item.getBasePriceSnapshot(),
                        customItem ? null : item.getIncludedImageCountSnapshot(), customItem ? null : item.getIncludedVideoCountSnapshot(),
                        item.getRequestedImageCount(), item.getRequestedVideoCount(), item.getPricingProfileSnapshot(),
                        item.getStandardVideoDurationSnapshot(), true,
                        dimensions, itemContext));
            }
            for (ChecklistPriceBreakdown.Line line : priced.lines())
                add(result, line.category(), label + " · " + line.description(), line.quantity(), line.unitPrice(), line.amount(),
                        item.getId());
        }
        scaleVisitLines(result, visitStart, occurrences);
        // OUTPUT, permit and other order-level charges are calculated once per order, never once per checklist/visit.
        orderLevelPrices.calculate(policy, OrderLevelPriceCalculator.Context.order(order), subtotal(result), base).stream()
                .map(OrderLevelPriceCalculator.Charge::line)
                .filter(line -> line.amount().signum() != 0)
                .forEach(result::add);

        BigDecimal total = subtotal(result);
        if (total.signum() < 0) throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED);
        // Work assessment is only forced for legacy custom items; v1 custom items are priced by the policy or quoted per item.
        return new PricingBreakdown(policy.getId(), policy.getPolicyVersion(), policy.getCurrency(), total,
                customWork, assessmentComplete,
                List.copyOf(result), List.copyOf(pendingQuotes));
    }

    private PricingPolicy resolvePolicy(Order order) {
        if (order.getPricingPolicyId() != null && !order.getPricingPolicyId().isBlank()) {
            PricingPolicy policy = policies.findWithRulesById(order.getPricingPolicyId())
                    .orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND));
            if (order.getPricingPolicyVersion() != null
                    && policy.getPolicyVersion() != order.getPricingPolicyVersion())
                throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED, "Order pricing policy version mismatch");
            return policy;
        }
        return policies.findEffectiveActive(Instant.now()).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND));
    }

    /** Bills the lines added since {@code from} once per visit: quantity and amount scale, the unit price does not. */
    private void scaleVisitLines(List<PricingBreakdownItem> items, int from, int occurrences) {
        if (occurrences <= 1) return;
        BigDecimal factor = BigDecimal.valueOf(occurrences);
        for (int i = from; i < items.size(); i++) {
            PricingBreakdownItem line = items.get(i);
            items.set(i, new PricingBreakdownItem(line.category(), line.description() + " (× " + occurrences + " lần)",
                    line.quantity().multiply(factor), line.unitPrice(), money(line.amount().multiply(factor)),
                    line.orderChecklistItemId()));
        }
    }

    private void applyRule(List<PricingBreakdownItem> out, PricingRule rule, BigDecimal value, BigDecimal base) {
        if (rule.getCalculationType() == PricingCalculationType.MANUAL_QUOTE)
            throw new ApiException(ErrorCode.CUSTOM_WORK_PRICING_INCOMPLETE, rule.getDescription());
        BigDecimal quantity = BigDecimal.ONE;
        BigDecimal amount;
        switch (rule.getCalculationType()) {
            case FIXED, ACTUAL_COST, RESOURCE_BASED -> amount = nz(rule.getFixedAmount());
            case PERCENT_OF_BASE -> amount = base.multiply(nz(rule.getPercentage())).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
            case PER_UNIT -> { quantity = value; amount = nz(rule.getFixedAmount()).multiply(value); }
            case PER_BLOCK, TIERED_BLOCK -> {
                BigDecimal chargeable = value.subtract(nz(rule.getMinValue())).max(BigDecimal.ZERO);
                quantity = chargeable.divide(positive(rule.getUnitSize()), 0, RoundingMode.CEILING);
                amount = nz(rule.getFixedAmount()).multiply(quantity);
                if (rule.getCalculationType() == PricingCalculationType.TIERED_BLOCK)
                    amount = amount.add(nz(rule.getBaseAmount()));
            }
            case MULTIPLIER -> amount = base.multiply(nz(rule.getMultiplier()).subtract(BigDecimal.ONE)).setScale(0, RoundingMode.HALF_UP);
            default -> throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED);
        }
        if (rule.getCategory() == PricingCategory.DISCOUNT && amount.signum() > 0) amount = amount.negate();
        BigDecimal unit = quantity.signum() == 0 ? ZERO : amount.divide(quantity, 0, RoundingMode.HALF_UP);
        add(out, rule.getCategory(), rule.getDescription(), quantity, unit, money(amount));
    }
    private void addFixed(List<PricingBreakdownItem> out, PricingRule rule, BigDecimal quantity) {
        BigDecimal unit = nz(rule.getFixedAmount());
        add(out, rule.getCategory(), rule.getDescription(), quantity, unit, money(unit.multiply(quantity)));
    }
    private Optional<PricingRule> ranged(PricingPolicy policy, PricingCategory category, BigDecimal value) {
        return enabled(policy, category).stream().filter(r -> (r.getMinValue() == null || value.compareTo(r.getMinValue()) > 0)
                && (r.getMaxValue() == null || value.compareTo(r.getMaxValue()) <= 0)).findFirst();
    }
    private Optional<PricingRule> keyed(PricingPolicy policy, PricingCategory category, String key) {
        return enabled(policy, category).stream().filter(r -> r.getRuleKey().equalsIgnoreCase(key)).findFirst();
    }
    private List<PricingRule> enabled(PricingPolicy policy, PricingCategory category) {
        return policy.getRules().stream().filter(PricingRule::isEnabled).filter(r -> r.getCategory() == category).toList();
    }
    private BigDecimal polygonHectares(Order order) {
        if (order.getTargetArea() == null) return BigDecimal.ZERO;
        Coordinate[] points = order.getTargetArea().getExteriorRing().getCoordinates();
        if (points.length < 4) return BigDecimal.ZERO;
        double lat0 = Arrays.stream(points).mapToDouble(c -> c.y).average().orElse(0);
        double sx = 111_320d * Math.cos(Math.toRadians(lat0)), sy = 110_540d, twiceArea = 0;
        for (int i = 0; i < points.length - 1; i++) twiceArea += points[i].x * sx * points[i + 1].y * sy - points[i + 1].x * sx * points[i].y * sy;
        return BigDecimal.valueOf(Math.abs(twiceArea) / 2d / 10_000d).setScale(3, RoundingMode.HALF_UP);
    }
    private String shorten(String text) {
        if (text == null) return "";
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
    private BigDecimal subtotal(List<PricingBreakdownItem> items) { return items.stream().map(PricingBreakdownItem::amount).reduce(ZERO, BigDecimal::add); }
    private void add(List<PricingBreakdownItem> out, PricingCategory c, String d, BigDecimal q, BigDecimal u, BigDecimal a) { if (a.signum() != 0 || c == PricingCategory.PACKAGE) out.add(new PricingBreakdownItem(c, d, q, u, a)); }
    private void add(List<PricingBreakdownItem> out, PricingCategory c, String d, BigDecimal q, BigDecimal u, BigDecimal a,
            String checklistItemId) {
        if (a.signum() != 0) out.add(new PricingBreakdownItem(c, d, q, u, a, checklistItemId));
    }
    private BigDecimal decimal(Double value) { return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value); }
    private BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal positive(BigDecimal value) { return value == null || value.signum() <= 0 ? BigDecimal.ONE : value; }
    private BigDecimal money(BigDecimal value) { return nz(value).setScale(0, RoundingMode.HALF_UP); }
}
