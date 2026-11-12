package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.pricing.domain.*;
import com.ondemandmonitoring.pricing.dto.*;
import com.ondemandmonitoring.pricing.enums.*;
import java.math.BigDecimal;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PricingPolicyService {
    private final PricingPolicyRepository policies;

    private com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository checklistDefinitions;

    @org.springframework.beans.factory.annotation.Autowired
    public void setChecklistDefinitions(
            com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository checklistDefinitions) {
        this.checklistDefinitions = checklistDefinitions;
    }

    private com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices;

    /** Optional collaborator: keeps the stored service default price current. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDefaultPrices(com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices) {
        this.defaultPrices = defaultPrices;
    }

    @Transactional(readOnly = true)
    public List<PricingPolicyResponse> list() { return policies.findAllByOrderByPolicyVersionDesc().stream().map(this::response).toList(); }

    @Transactional
    public PricingPolicyResponse createDraft(PricingPolicyRequest request) {
        int version = policies.findAllByOrderByPolicyVersionDesc().stream().mapToInt(PricingPolicy::getPolicyVersion).max().orElse(0) + 1;
        PricingPolicy policy = new PricingPolicy(); policy.setPolicyVersion(version); policy.setStatus(PricingPolicyStatus.DRAFT);
        apply(policy, request); return response(policies.save(policy));
    }

    @Transactional
    public PricingPolicyResponse cloneAsDraft(String id) {
        PricingPolicy source = policies.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND));
        if (source.getStatus() != PricingPolicyStatus.ACTIVE)
            throw invalid("Only the active pricing policy can be cloned");
        int version = policies.findAllByOrderByPolicyVersionDesc().stream()
                .mapToInt(PricingPolicy::getPolicyVersion).max().orElse(0) + 1;
        PricingPolicy draft = new PricingPolicy();
        draft.setName(source.getName() + " v" + version);
        draft.setPolicyVersion(version);
        draft.setStatus(PricingPolicyStatus.DRAFT);
        draft.setCurrency(source.getCurrency());
        draft.setManagerMaxDiscountPercent(source.getManagerMaxDiscountPercent());
        draft.setManagerMaxMarkupPercent(source.getManagerMaxMarkupPercent());
        draft.setHighRiskDiscountPercent(source.getHighRiskDiscountPercent());
        draft.setMinimumPriceRatio(source.getMinimumPriceRatio());
        draft.setMinimumPrice(source.getMinimumPrice());
        draft.replaceRules(migrateLegacyCustomerAdded(source.getRules()).stream().map(this::copyRule).toList());
        return response(policies.save(draft));
    }

    @Transactional
    public PricingPolicyResponse updateDraft(String id, PricingPolicyRequest request) {
        PricingPolicy policy = policies.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND,
                "Pricing policy draft no longer exists. Reload the page to get the current version."));
        requireDraft(policy); apply(policy, request); return response(policy);
    }

    @Transactional
    public PricingPolicyResponse publish(String id) {
        PricingPolicy policy = policies.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND,
                "Pricing policy draft no longer exists. Reload the page to get the current version."));
        requireDraft(policy); Instant now = Instant.now();
        for (PricingPolicy active : policies.findAllByStatus(PricingPolicyStatus.ACTIVE)) {
            active.setStatus(PricingPolicyStatus.SUPERSEDED); active.setEffectiveTo(now);
        }
        policy.setStatus(PricingPolicyStatus.ACTIVE);
        if (policy.getEffectiveFrom() == null || policy.getEffectiveFrom().isAfter(now)) policy.setEffectiveFrom(now);
        policies.flush();
        // New included-media prices, tiers and difficulty rules change what every service's default checklists cost.
        if (defaultPrices != null) defaultPrices.refreshAll();
        return response(policy);
    }

    private void apply(PricingPolicy p, PricingPolicyRequest r) {
        if (r.effectiveFrom() != null && r.effectiveTo() != null && !r.effectiveTo().isAfter(r.effectiveFrom()))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "effectiveTo must be after effectiveFrom");
        if (r.highRiskDiscountPercent().compareTo(r.managerMaxDiscountPercent()) > 0)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "High-risk threshold cannot exceed maximum discount");
        p.setName(r.name().trim()); p.setCurrency("VND"); p.setEffectiveFrom(r.effectiveFrom()); p.setEffectiveTo(r.effectiveTo());
        p.setManagerMaxDiscountPercent(r.managerMaxDiscountPercent()); p.setManagerMaxMarkupPercent(r.managerMaxMarkupPercent());
        p.setHighRiskDiscountPercent(r.highRiskDiscountPercent()); p.setMinimumPriceRatio(r.minimumPriceRatio()); p.setMinimumPrice(r.minimumPrice());
        List<PricingRule> rules = new ArrayList<>(); Set<String> keys = new HashSet<>();
        for (PricingRuleRequest rr : r.rules()) {
            String target = rr.checklistDefinitionId() == null ? "*" : rr.checklistDefinitionId();
            if (!keys.add(rr.category() + ":" + target + ":" + rr.ruleKey().toUpperCase(Locale.ROOT)))
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Duplicate pricing rule key");
            PricingRule rule = new PricingRule(); rule.setCategory(rr.category()); rule.setCalculationType(rr.calculationType());
            rule.setRuleKey(rr.ruleKey().trim().toUpperCase(Locale.ROOT)); rule.setDescription(rr.description().trim());
            rule.setMinValue(rr.minValue()); rule.setMaxValue(rr.maxValue()); rule.setFixedAmount(rr.fixedAmount()); rule.setBaseAmount(rr.baseAmount());
            rule.setPercentage(rr.percentage()); rule.setUnitSize(rr.unitSize()); rule.setMultiplier(rr.multiplier());
            rule.setPriority(rr.priority()); rule.setEnabled(rr.enabled());
            rule.setPricingProfile(rr.pricingProfile() == null || rr.pricingProfile().isBlank() ? null
                    : rr.pricingProfile().trim().toUpperCase(Locale.ROOT));
            rule.setChecklistDefinitionId(rr.checklistDefinitionId() == null || rr.checklistDefinitionId().isBlank()
                    ? null : rr.checklistDefinitionId().trim());
            rule.setMediaComponent(rr.mediaComponent()); rule.setIncludedQuantity(rr.includedQuantity());
            rule.setRoundingMode(rr.roundingMode());
            validateRule(rule); rules.add(rule);
        }
        validateMediaTiers(rules);
        validateDimensionTiers(rules);
        p.replaceRules(rules);
    }
    private static final java.util.regex.Pattern PROFILE = java.util.regex.Pattern.compile("^[A-Z0-9_]{1,60}$");

    private void validateRule(PricingRule r) {
        for (BigDecimal amount : new BigDecimal[] {r.getFixedAmount(), r.getBaseAmount(), r.getMinValue(), r.getMaxValue(), r.getUnitSize()})
            if (amount != null && amount.signum() < 0) throw invalid("Pricing amounts, ranges and block sizes cannot be negative");
        for (BigDecimal money : new BigDecimal[] {r.getFixedAmount(), r.getBaseAmount()})
            if (money != null && money.stripTrailingZeros().scale() > 0) throw invalid("Prices are whole VND amounts");
        if (r.getMinValue() != null && r.getMaxValue() != null && r.getMaxValue().compareTo(r.getMinValue()) <= 0)
            throw invalid("Rule '" + r.getRuleKey() + "' needs maxValue greater than minValue");
        if (r.getCategory() != PricingCategory.MEDIA) {
            if (r.getPricingProfile() != null || r.getMediaComponent() != null || r.getIncludedQuantity() != null
                    || r.getChecklistDefinitionId() != null)
                throw invalid("Profile, checklist target, media component and allowance apply to MEDIA rules only");
            boolean block = r.getCalculationType() == PricingCalculationType.PER_BLOCK
                    || r.getCalculationType() == PricingCalculationType.TIERED_BLOCK;
            if (block && (r.getUnitSize() == null || r.getUnitSize().signum() <= 0))
                throw invalid("Rule '" + r.getRuleKey() + "' needs a block size greater than 0");
            boolean dimensional = r.getCategory() == PricingCategory.AREA
                    || r.getCategory() == PricingCategory.DISTANCE
                    || r.getCategory() == PricingCategory.ALTITUDE;
            if (r.getRoundingMode() != null && (!dimensional || !block))
                throw invalid("Rounding applies only to block-based AREA, DISTANCE or ALTITUDE rules");
            if (r.getCalculationType() == PricingCalculationType.FIXED && r.getFixedAmount() == null)
                throw invalid("Fixed rule '" + r.getRuleKey() + "' needs a price");
            if (r.getCalculationType() == PricingCalculationType.PERCENT_OF_BASE
                    && (r.getPercentage() == null || r.getPercentage().signum() < 0))
                throw invalid("Percentage rule '" + r.getRuleKey() + "' needs a non-negative percentage");
            if (r.getCalculationType() == PricingCalculationType.MULTIPLIER
                    && (r.getMultiplier() == null || r.getMultiplier().signum() <= 0))
                throw invalid("Multiplier rule '" + r.getRuleKey() + "' needs a multiplier greater than 0");
            return;
        }
        if (r.getPricingProfile() == null || !PROFILE.matcher(r.getPricingProfile()).matches())
            throw invalid("MEDIA rule '" + r.getRuleKey() + "' needs a pricing profile of letters, digits and underscore");
        if (r.getMediaComponent() == null) throw invalid("MEDIA rule '" + r.getRuleKey() + "' needs a media component");
        if ((r.getMediaComponent() == MediaPricingComponent.INCLUDED_AREA || r.getMediaComponent() == MediaPricingComponent.EXTRA_AREA)
                && !(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE.equals(r.getPricingProfile())
                || ChecklistPriceCalculator.LEGACY_CUSTOM_PROFILE.equals(r.getPricingProfile())))
            throw invalid("Included area and extra-area price apply to the customer-added profile only");
        if (r.getChecklistDefinitionId() != null) {
            if (checklistDefinitions == null || !checklistDefinitions.existsById(r.getChecklistDefinitionId()))
                throw invalid("MEDIA rule '" + r.getRuleKey() + "' targets an unknown checklist");
            if (r.getMediaComponent() != MediaPricingComponent.EXTRA_IMAGE
                    && r.getMediaComponent() != MediaPricingComponent.EXTRA_VIDEO)
                throw invalid("Checklist-specific rules apply only to extra image or extra video pricing");
        }
        if (r.getFixedAmount() == null) throw invalid("MEDIA rule '" + r.getRuleKey() + "' needs a price (fixedAmount)");
        boolean tier = r.getMediaComponent() == MediaPricingComponent.EXTRA_IMAGE || r.getMediaComponent() == MediaPricingComponent.EXTRA_VIDEO;
        if (tier) {
            if (r.getUnitSize() == null || r.getUnitSize().signum() <= 0 || r.getUnitSize().stripTrailingZeros().scale() > 0)
                throw invalid("Extra-media tier '" + r.getRuleKey() + "' needs a whole block size of at least 1");
            if (r.getMinValue() == null) r.setMinValue(BigDecimal.ZERO);
            if (r.getRoundingMode() == null) r.setRoundingMode(PricingRounding.CEILING);
            r.setCalculationType(PricingCalculationType.PER_BLOCK);
            if (r.getIncludedQuantity() != null) throw invalid("Allowance applies to included-media rules only");
        } else {
            if (r.getMinValue() != null || r.getMaxValue() != null || r.getUnitSize() != null || r.getRoundingMode() != null)
                throw invalid("Only extra-media tiers have ranges, block sizes and rounding");
            r.setCalculationType(PricingCalculationType.FIXED);
            boolean included = r.getMediaComponent() == MediaPricingComponent.INCLUDED_IMAGE || r.getMediaComponent() == MediaPricingComponent.INCLUDED_VIDEO
                    || r.getMediaComponent() == MediaPricingComponent.INCLUDED_AREA;
            if (r.getIncludedQuantity() != null && !(included
                    && (ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE.equals(r.getPricingProfile())
                    || ChecklistPriceCalculator.LEGACY_CUSTOM_PROFILE.equals(r.getPricingProfile()))))
                throw invalid("An allowance is configured on the checklist, except for the CUSTOM profile");
        }
    }

    /** Tiers of one profile/component must start at 0, be contiguous and never overlap; only the last may be open-ended. */
    private void validateMediaTiers(List<PricingRule> rules) {
        Map<String, List<PricingRule>> groups = new HashMap<>();
        for (PricingRule r : rules) {
            if (r.getCategory() != PricingCategory.MEDIA) continue;
            if (r.isEnabled() && (r.getMediaComponent() == MediaPricingComponent.EXTRA_IMAGE || r.getMediaComponent() == MediaPricingComponent.EXTRA_VIDEO))
                groups.computeIfAbsent(Optional.ofNullable(r.getChecklistDefinitionId()).orElse("*") + ":"
                        + r.getPricingProfile() + ":" + r.getMediaComponent(), k -> new ArrayList<>()).add(r);
        }
        for (var entry : groups.entrySet()) {
            List<PricingRule> tiers = entry.getValue().stream().sorted(Comparator.comparing(PricingRule::getMinValue)).toList();
            BigDecimal cursor = BigDecimal.ZERO;
            for (int i = 0; i < tiers.size(); i++) {
                PricingRule tier = tiers.get(i);
                if (tier.getMinValue().compareTo(cursor) < 0) throw invalid("Price tiers overlap for " + entry.getKey());
                if (tier.getMinValue().compareTo(cursor) > 0) throw invalid("Price tiers for " + entry.getKey() + " leave a gap before " + tier.getMinValue().stripTrailingZeros().toPlainString());
                if (tier.getMaxValue() == null) {
                    if (i != tiers.size() - 1) throw invalid("Only the last price tier of " + entry.getKey() + " may be open-ended");
                } else cursor = tier.getMaxValue();
            }
        }
    }
    private void validateDimensionTiers(List<PricingRule> rules) {
        for (PricingCategory category : List.of(PricingCategory.AREA, PricingCategory.DISTANCE,
                PricingCategory.ALTITUDE)) {
            List<PricingRule> tiers = rules.stream().filter(PricingRule::isEnabled)
                    .filter(rule -> rule.getCategory() == category)
                    .filter(rule -> rule.getCalculationType() == PricingCalculationType.PER_BLOCK
                            || rule.getCalculationType() == PricingCalculationType.TIERED_BLOCK)
                    .sorted(Comparator.comparing(rule -> Optional.ofNullable(rule.getMinValue()).orElse(BigDecimal.ZERO)))
                    .toList();
            BigDecimal previousEnd = null;
            boolean unlimited = false;
            for (PricingRule tier : tiers) {
                BigDecimal start = Optional.ofNullable(tier.getMinValue()).orElse(BigDecimal.ZERO);
                if (unlimited || previousEnd != null && start.compareTo(previousEnd) < 0)
                    throw invalid("Price tiers overlap for " + category);
                if (tier.getMaxValue() == null) unlimited = true;
                else previousEnd = tier.getMaxValue();
            }
        }
    }
    private ApiException invalid(String message) { return new ApiException(ErrorCode.INVALID_REQUEST, message); }

    private void requireDraft(PricingPolicy p) { if (p.getStatus() != PricingPolicyStatus.DRAFT) throw new ApiException(ErrorCode.INVALID_REQUEST, "Published pricing policies are immutable"); }
    private static final Set<MediaPricingComponent> CUSTOMER_ADDED_COMPONENTS = EnumSet.of(MediaPricingComponent.CHECKLIST_BASE,
            MediaPricingComponent.INCLUDED_IMAGE, MediaPricingComponent.INCLUDED_VIDEO, MediaPricingComponent.INCLUDED_AREA,
            MediaPricingComponent.EXTRA_AREA);

    /**
     * Customer-added prices used to live under the CUSTOM profile. A new draft keeps one set only: the legacy rule becomes the
     * GENERIC_CUSTOMER_ADDED one, or is dropped when the policy already has that one.
     */
    private List<PricingRule> migrateLegacyCustomerAdded(List<PricingRule> rules) {
        String generic = ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE;
        String legacy = ChecklistPriceCalculator.LEGACY_CUSTOM_PROFILE;
        List<PricingRule> out = new ArrayList<>();
        for (PricingRule rule : rules) {
            boolean old = rule.getCategory() == PricingCategory.MEDIA && legacy.equals(rule.getPricingProfile())
                    && rule.getChecklistDefinitionId() == null && CUSTOMER_ADDED_COMPONENTS.contains(rule.getMediaComponent());
            if (!old) { out.add(rule); continue; }
            boolean covered = rules.stream().anyMatch(other -> other.getCategory() == PricingCategory.MEDIA
                    && generic.equals(other.getPricingProfile()) && other.getChecklistDefinitionId() == null
                    && other.getMediaComponent() == rule.getMediaComponent());
            if (covered) continue;
            PricingRule moved = copyRule(rule);
            moved.setPricingProfile(generic);
            moved.setRuleKey(moved.getRuleKey().replaceFirst("^(?i)" + legacy + ":", generic + ":"));
            if (moved.getDescription() != null) moved.setDescription(moved.getDescription().replaceFirst("^(?i)" + legacy, generic));
            out.add(moved);
        }
        return out;
    }
    private PricingRule copyRule(PricingRule source) {
        PricingRule rule = new PricingRule();
        rule.setCategory(source.getCategory()); rule.setCalculationType(source.getCalculationType());
        rule.setRuleKey(source.getRuleKey()); rule.setDescription(source.getDescription());
        rule.setMinValue(source.getMinValue()); rule.setMaxValue(source.getMaxValue());
        rule.setFixedAmount(source.getFixedAmount()); rule.setBaseAmount(source.getBaseAmount());
        rule.setPercentage(source.getPercentage()); rule.setUnitSize(source.getUnitSize());
        rule.setMultiplier(source.getMultiplier()); rule.setPriority(source.getPriority());
        rule.setEnabled(source.isEnabled()); rule.setPricingProfile(source.getPricingProfile());
        rule.setChecklistDefinitionId(source.getChecklistDefinitionId());
        rule.setMediaComponent(source.getMediaComponent()); rule.setIncludedQuantity(source.getIncludedQuantity());
        rule.setRoundingMode(source.getRoundingMode());
        return rule;
    }
    private PricingPolicyResponse response(PricingPolicy p) { return new PricingPolicyResponse(p.getId(), p.getName(), p.getPolicyVersion(), p.getStatus(), p.getCurrency(), p.getEffectiveFrom(), p.getEffectiveTo(), p.getManagerMaxDiscountPercent(), p.getManagerMaxMarkupPercent(), p.getHighRiskDiscountPercent(), p.getMinimumPriceRatio(), p.getMinimumPrice(), p.getRules().stream().map(this::response).toList()); }
    private PricingRuleResponse response(PricingRule r) {
        String checklistName = r.getChecklistDefinitionId() == null || checklistDefinitions == null ? null
                : checklistDefinitions.findById(r.getChecklistDefinitionId()).map(definition -> definition.getContent()).orElse(null);
        return new PricingRuleResponse(r.getId(), r.getCategory(), r.getCalculationType(), r.getRuleKey(),
                r.getDescription(), r.getMinValue(), r.getMaxValue(), r.getFixedAmount(), r.getBaseAmount(),
                r.getPercentage(), r.getUnitSize(), r.getMultiplier(), r.getPriority(), r.isEnabled(),
                r.getPricingProfile(), r.getMediaComponent(), r.getIncludedQuantity(), r.getRoundingMode(),
                r.getChecklistDefinitionId(), checklistName);
    }
}
