package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown.Line;
import com.ondemandmonitoring.pricing.enums.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for what one checklist costs. It reads only the stored pricing policy
 * (MEDIA rules per pricing profile) and the checklist configuration handed in, so the Admin preview,
 * the customer quote and the Manager quote cannot disagree.
 *
 * <pre>
 * checklist price = base + difficulty rule + included image price + included video price
 *                 + extra image tiers(max(0, requested - included)) + extra video tiers(...)
 * </pre>
 * A component is billed once; the included allowance is never re-billed through the extra tiers.
 */
@Component
public class ChecklistPriceCalculator {
    public static final String DEFAULT_PROFILE = "DEFAULT";
    /** Pricing profile used for free-text requirements added by a customer. */
    public static final String GENERIC_CUSTOMER_ADDED_PROFILE = "GENERIC_CUSTOMER_ADDED";
    /** Kept only so policies published before the profile rename can still price historical orders. */
    public static final String LEGACY_CUSTOM_PROFILE = "CUSTOM";
    public static final int MAX_MEDIA_COUNT = 500;

    /**
     * @param catalogBasePrice price of the checklist work; ignored for custom checklists (priced by the CUSTOM profile).
     * @param includedImages   allowance; for custom checklists {@code null} means the CUSTOM profile allowance.
     * @param requestedImages  {@code null} means "exactly the included allowance".
     */
    public record PricingDimensions(boolean areaEnabled, boolean lengthEnabled, boolean altitudeEnabled,
            BigDecimal includedAreaHa, BigDecimal includedLengthKm, BigDecimal includedAltitudeM) {
        public static PricingDimensions disabled() {
            return new PricingDimensions(false, false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record RequestContext(BigDecimal areaHa, BigDecimal lengthKm, BigDecimal altitudeM) {
        public static RequestContext empty() {
            return new RequestContext(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record Input(String checklistId, String content, ChecklistDifficulty difficulty, boolean custom,
            BigDecimal catalogBasePrice, Integer includedImages, Integer includedVideos,
            Integer requestedImages, Integer requestedVideos, String pricingProfile, Integer standardVideoDurationSeconds,
            boolean includeDifficulty, PricingDimensions dimensions, RequestContext context) {
        public Input(String checklistId, String content, ChecklistDifficulty difficulty, boolean custom,
                BigDecimal catalogBasePrice, Integer includedImages, Integer includedVideos,
                Integer requestedImages, Integer requestedVideos, String pricingProfile, Integer standardVideoDurationSeconds,
                boolean includeDifficulty) {
            this(checklistId, content, difficulty, custom, catalogBasePrice, includedImages, includedVideos,
                    requestedImages, requestedVideos, pricingProfile, standardVideoDurationSeconds, includeDifficulty,
                    PricingDimensions.disabled(), RequestContext.empty());
        }
    }

    public ChecklistPriceBreakdown price(PricingPolicy policy, Input in) {
        String profile = in.custom()
                ? normalizeProfile(in.pricingProfile() == null ? GENERIC_CUSTOMER_ADDED_PROFILE : in.pricingProfile())
                : normalizeProfile(in.pricingProfile());
        List<Line> lines = new ArrayList<>();

        BigDecimal base = in.custom() ? customBase(policy, profile) : money(in.catalogBasePrice());
        if (base.signum() < 0) throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED);
        if (base.signum() > 0) lines.add(new Line(in.custom() ? "CUSTOM_BASE" : "BASE", PricingCategory.CHECKLIST,
                in.custom() ? "Checklist ngoài danh mục" : "Giá cơ bản checklist", BigDecimal.ONE, base, base, null, null, null, null));

        if (in.includeDifficulty()) {
            String key = (in.difficulty() == null ? ChecklistDifficulty.BASIC : in.difficulty()).name();
            rules(policy, PricingCategory.CHECKLIST, null, null).stream().filter(r -> r.getRuleKey().equalsIgnoreCase(key)).findFirst()
                    .ifPresent(rule -> {
                        BigDecimal amount = money(rule.getFixedAmount());
                        if (amount.signum() != 0) lines.add(new Line("DIFFICULTY", PricingCategory.CHECKLIST, rule.getDescription(),
                                BigDecimal.ONE, amount, amount, null, null, null, null));
                    });
        }

        int includedImages = allowance(policy, profile, MediaPricingComponent.INCLUDED_IMAGE, in.includedImages());
        int includedVideos = allowance(policy, profile, MediaPricingComponent.INCLUDED_VIDEO, in.includedVideos());
        int requestedImages = in.requestedImages() == null ? includedImages : in.requestedImages();
        int requestedVideos = in.requestedVideos() == null ? includedVideos : in.requestedVideos();
        if (requestedImages < 0 || requestedVideos < 0 || requestedImages > MAX_MEDIA_COUNT || requestedVideos > MAX_MEDIA_COUNT)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Requested media counts must be between 0 and " + MAX_MEDIA_COUNT);

        includedLine(policy, profile, MediaPricingComponent.INCLUDED_IMAGE, includedImages, "ảnh").ifPresent(lines::add);
        includedLine(policy, profile, MediaPricingComponent.INCLUDED_VIDEO, includedVideos, "video").ifPresent(lines::add);

        int extraImages = Math.max(0, requestedImages - includedImages);
        int extraVideos = Math.max(0, requestedVideos - includedVideos);
        // One shared price list for extra images and videos. Customer-added work never has a list of its own, so its
        // extra media is priced exactly like the same quantity on a catalog checklist.
        String mediaProfile = in.custom() ? DEFAULT_PROFILE : profile;
        tierLines(policy, in.checklistId(), mediaProfile, MediaPricingComponent.EXTRA_IMAGE, extraImages, "ảnh bổ sung", lines);
        tierLines(policy, in.checklistId(), mediaProfile, MediaPricingComponent.EXTRA_VIDEO, extraVideos, "video bổ sung", lines);

        PricingDimensions dimensions = in.dimensions() == null ? PricingDimensions.disabled() : in.dimensions();
        RequestContext context = in.context() == null ? RequestContext.empty() : in.context();
        if (dimensions.areaEnabled()) dimensionLines(policy, profile, PricingCategory.AREA, "AREA",
                positive(context.areaHa()), positive(dimensions.includedAreaHa()),
                "diện tích bổ sung", lines);
        if (dimensions.lengthEnabled()) dimensionLines(policy, profile, PricingCategory.DISTANCE, "LENGTH",
                positive(context.lengthKm()), positive(dimensions.includedLengthKm()),
                "chiều dài bổ sung", lines);
        if (dimensions.altitudeEnabled()) dimensionLines(policy, profile, PricingCategory.ALTITUDE, "ALTITUDE",
                positive(context.altitudeM()), positive(dimensions.includedAltitudeM()),
                "độ cao bổ sung", lines);

        if (in.custom()) customAreaLines(policy, profile, context.areaHa(), lines);

        BigDecimal subtotal = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ChecklistPriceBreakdown(in.checklistId(), in.content(), profile, in.custom(), includedImages, includedVideos,
                requestedImages, requestedVideos, extraImages, extraVideos, in.standardVideoDurationSeconds(), List.copyOf(lines), subtotal);
    }

    /** True when the policy itself prices checklists outside the catalog (a CUSTOM profile CHECKLIST_BASE rule). */
    public boolean hasCustomRule(PricingPolicy policy) {
        return policy != null && (!rules(policy, PricingCategory.MEDIA, GENERIC_CUSTOMER_ADDED_PROFILE,
                MediaPricingComponent.CHECKLIST_BASE).isEmpty()
                || !rules(policy, PricingCategory.MEDIA, LEGACY_CUSTOM_PROFILE,
                MediaPricingComponent.CHECKLIST_BASE).isEmpty());
    }

    /**
     * A checklist the policy cannot price. Until the Manager quotes it the price is pending (and says so); once quoted the
     * Manager's amount is the whole price of the checklist.
     */
    public ChecklistPriceBreakdown managerQuoted(String content, BigDecimal quotedPrice, Integer requestedImages, Integer requestedVideos) {
        List<Line> lines = new ArrayList<>();
        BigDecimal amount = quotedPrice == null ? BigDecimal.ZERO : money(quotedPrice);
        if (quotedPrice != null) lines.add(new Line("MANAGER_QUOTE", PricingCategory.CHECKLIST, "Giá do Manager báo", BigDecimal.ONE, amount, amount,
                null, null, null, null));
        return new ChecklistPriceBreakdown(null, content, GENERIC_CUSTOMER_ADDED_PROFILE, true, 0, 0, requestedImages == null ? 0 : requestedImages,
                requestedVideos == null ? 0 : requestedVideos, 0, 0, null, List.copyOf(lines), amount, quotedPrice == null);
    }

    /** Hectares included in the base price of customer-added work. */
    public int customIncludedAreaHa(PricingPolicy policy) {
        return profileRules(policy, PricingCategory.MEDIA, GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_AREA)
                .stream().findFirst().map(PricingRule::getIncludedQuantity).orElse(0);
    }

    /** Allowance a custom checklist gets from policy; used when the order snapshot is captured. */
    public int customAllowance(PricingPolicy policy, MediaPricingComponent component) {
        return allowance(policy, GENERIC_CUSTOMER_ADDED_PROFILE, component, null);
    }

    private BigDecimal customBase(PricingPolicy policy, String profile) {
        Optional<PricingRule> rule = profileRules(policy, PricingCategory.MEDIA, profile,
                MediaPricingComponent.CHECKLIST_BASE).stream().findFirst();
        if (rule.isPresent()) return money(rule.get().getFixedAmount());
        // No CUSTOM rule means policy configuration is incomplete; a Manager never invents the baseline amount.
        throw new ApiException(ErrorCode.CUSTOM_CHECKLIST_PRICING_NOT_CONFIGURED,
                "Customer-added checklist pricing is not configured. An administrator must publish a "
                        + GENERIC_CUSTOMER_ADDED_PROFILE + " checklist base rule.");
    }

    private int allowance(PricingPolicy policy, String profile, MediaPricingComponent component, Integer configured) {
        if (configured != null) {
            if (configured < 0 || configured > MAX_MEDIA_COUNT) throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED);
            return configured;
        }
        if (!GENERIC_CUSTOMER_ADDED_PROFILE.equals(profile) && !LEGACY_CUSTOM_PROFILE.equals(profile)) return 0;
        return profileRules(policy, PricingCategory.MEDIA, profile, component).stream().findFirst()
                .map(PricingRule::getIncludedQuantity).orElse(0);
    }

    private Optional<Line> includedLine(PricingPolicy policy, String profile, MediaPricingComponent component, int allowance, String noun) {
        if (allowance <= 0) return Optional.empty();
        return profileRules(policy, PricingCategory.MEDIA, profile, component).stream().findFirst().flatMap(rule -> {
            BigDecimal amount = money(rule.getFixedAmount());
            if (amount.signum() == 0) return Optional.empty();
            return Optional.of(new Line(component.name(), PricingCategory.MEDIA, "Đã bao gồm " + allowance + " " + noun,
                    BigDecimal.ONE, amount, amount, null, null, null, null));
        });
    }

    private void tierLines(PricingPolicy policy, String checklistId, String profile,
            MediaPricingComponent component, int extra, String noun, List<Line> out) {
        if (extra <= 0) return;
        List<PricingRule> tiers = targetedProfileRules(policy, PricingCategory.MEDIA, checklistId, profile, component).stream()
                .sorted(Comparator.comparing((PricingRule r) -> nz(r.getMinValue())).thenComparingInt(PricingRule::getPriority)).toList();
        if (tiers.isEmpty()) throw new ApiException(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED,
                "Cannot price " + extra + " " + noun + ": pricing profile " + profile + " has no price tier for it. "
                        + "An administrator must add a tier starting at 0 for profile " + profile + " in the pricing policy and publish it; "
                        + "alternatively request no more than the included allowance.");
        BigDecimal total = BigDecimal.valueOf(extra);
        BigDecimal covered = BigDecimal.ZERO;
        for (PricingRule tier : tiers) {
            if (covered.compareTo(total) >= 0) break;
            BigDecimal lo = nz(tier.getMinValue());
            BigDecimal hi = tier.getMaxValue();
            // Tiers must be contiguous from 0; a gap would silently make that range free.
            if (lo.compareTo(covered) != 0) break;
            BigDecimal upper = hi == null ? total : hi.min(total);
            BigDecimal quantity = upper.subtract(lo);
            if (quantity.signum() <= 0) break;
            covered = upper;
            BigDecimal size = tier.getUnitSize() == null || tier.getUnitSize().signum() <= 0 ? BigDecimal.ONE : tier.getUnitSize();
            PricingRounding rounding = tier.getRoundingMode() == null ? PricingRounding.CEILING : tier.getRoundingMode();
            RoundingMode mode = switch (rounding) {
                case CEILING -> RoundingMode.CEILING;
                case FLOOR -> RoundingMode.FLOOR;
                case HALF_UP -> RoundingMode.HALF_UP;
            };
            BigDecimal blocks = quantity.divide(size, 0, mode);
            BigDecimal unit = money(tier.getFixedAmount());
            BigDecimal amount = unit.multiply(blocks);
            String range = hi == null ? "từ " + lo.stripTrailingZeros().toPlainString() + " trở lên"
                    : "từ " + lo.stripTrailingZeros().toPlainString() + " đến " + hi.stripTrailingZeros().toPlainString();
            out.add(new Line(component.name(), PricingCategory.MEDIA,
                    "Phần " + noun + ": " + quantity.stripTrailingZeros().toPlainString() + " (" + range + ", mỗi khối "
                            + size.stripTrailingZeros().toPlainString() + ")",
                    blocks, unit, amount, lo.intValue(), hi == null ? null : hi.intValue(), size.intValue(), rounding.name()));
        }
        if (covered.compareTo(total) < 0)
            throw new ApiException(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED,
                    "Cannot price " + extra + " " + noun + ": the price tiers of profile " + profile + " only cover up to "
                            + covered.stripTrailingZeros().toPlainString() + ". An administrator must extend the tiers (contiguous from 0, "
                            + "last tier open-ended) in the pricing policy and publish it; alternatively request fewer.");
    }

    /**
     * Customer-added work covers the order's whole area. The base price includes an allowance of hectares; each hectare
     * above it is charged the policy's flat rate. Nothing is charged while the area is within the allowance.
     */
    private void customAreaLines(PricingPolicy policy, String profile, BigDecimal areaHa, List<Line> out) {
        BigDecimal area = positive(areaHa);
        if (area.signum() == 0) return;
        BigDecimal included = BigDecimal.valueOf(profileRules(policy, PricingCategory.MEDIA, profile, MediaPricingComponent.INCLUDED_AREA)
                .stream().findFirst().map(PricingRule::getIncludedQuantity).orElse(0));
        BigDecimal extra = area.subtract(included).max(BigDecimal.ZERO);
        if (extra.signum() == 0) return;
        PricingRule rate = profileRules(policy, PricingCategory.MEDIA, profile, MediaPricingComponent.EXTRA_AREA).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED,
                        "Cannot price " + extra.stripTrailingZeros().toPlainString() + " ha above the included area: the pricing policy has no "
                                + "price per extra hectare for customer-added work. An administrator must add it and publish the policy."));
        BigDecimal unit = money(rate.getFixedAmount());
        BigDecimal amount = money(unit.multiply(extra));
        if (amount.signum() == 0) return;
        out.add(new Line("EXTRA_AREA", PricingCategory.AREA,
                "Phụ thu diện tích vượt định mức: " + extra.stripTrailingZeros().toPlainString() + " ha (đã bao gồm "
                        + included.stripTrailingZeros().toPlainString() + " ha)",
                extra, unit, amount, null, null, null, null));
    }

    private void dimensionLines(PricingPolicy policy, String profile, PricingCategory category, String component,
            BigDecimal value, BigDecimal included, String noun, List<Line> out) {
        if (value.compareTo(included) <= 0) return;
        List<PricingRule> tiers = policy.getRules().stream().filter(PricingRule::isEnabled)
                .filter(rule -> rule.getCategory() == category)
                .filter(rule -> rule.getPricingProfile() == null || rule.getPricingProfile().isBlank()
                        || profile.equalsIgnoreCase(rule.getPricingProfile()))
                .sorted(Comparator.comparing((PricingRule r) -> nz(r.getMinValue())).thenComparingInt(PricingRule::getPriority))
                .toList();
        PricingRule tier = tiers.stream()
                .filter(rule -> (rule.getMinValue() == null || value.compareTo(rule.getMinValue()) > 0)
                        && (rule.getMaxValue() == null || value.compareTo(rule.getMaxValue()) <= 0))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.PRICING_RULE_NOT_FOUND,
                        "Cannot price " + noun + " at " + value.stripTrailingZeros().toPlainString()
                                + ": no published " + category + " range covers it."));
        if (tier.getCalculationType() == PricingCalculationType.MANUAL_QUOTE)
            throw new ApiException(ErrorCode.CUSTOM_WORK_PRICING_INCOMPLETE, tier.getDescription());

        BigDecimal lo = positive(tier.getMinValue());
        BigDecimal hi = tier.getMaxValue();
        // The checklist allowance and the policy range floor describe the same free threshold in the common case.
        // Taking the greater one prevents either threshold from being charged, without subtracting both.
        BigDecimal chargeable = value.subtract(included.max(lo)).max(BigDecimal.ZERO);
        BigDecimal unitSize = positive(tier.getUnitSize());
        if (unitSize.signum() == 0) unitSize = BigDecimal.ONE;
        PricingRounding rounding = tier.getRoundingMode() == null ? PricingRounding.CEILING : tier.getRoundingMode();
        RoundingMode roundingMode = switch (rounding) {
            case CEILING -> RoundingMode.CEILING;
            case FLOOR -> RoundingMode.FLOOR;
            case HALF_UP -> RoundingMode.HALF_UP;
        };
        BigDecimal checklistBase = out.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal billedQuantity = BigDecimal.ONE;
        BigDecimal unitPrice = money(tier.getFixedAmount());
        BigDecimal amount;
        switch (tier.getCalculationType()) {
            case FIXED, ACTUAL_COST, RESOURCE_BASED -> amount = unitPrice;
            case PERCENT_OF_BASE -> amount = money(checklistBase.multiply(nz(tier.getPercentage()))
                    .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP));
            case PER_UNIT -> {
                billedQuantity = chargeable;
                amount = money(unitPrice.multiply(billedQuantity));
            }
            case PER_BLOCK, TIERED_BLOCK -> {
                billedQuantity = chargeable.divide(unitSize, 0, roundingMode);
                amount = money(unitPrice.multiply(billedQuantity));
                if (tier.getCalculationType() == PricingCalculationType.TIERED_BLOCK)
                    amount = amount.add(money(tier.getBaseAmount()));
            }
            case MULTIPLIER -> amount = money(checklistBase.multiply(nz(tier.getMultiplier()).subtract(BigDecimal.ONE)));
            default -> throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED,
                    "Unsupported " + category + " calculation type: " + tier.getCalculationType());
        }
        if (amount.signum() == 0) return;
        BigDecimal displayUnit = switch (tier.getCalculationType()) {
            case PER_UNIT, PER_BLOCK, TIERED_BLOCK -> unitPrice;
            default -> amount;
        };
        out.add(new Line(component, category,
                tier.getDescription() == null || tier.getDescription().isBlank() ? noun : tier.getDescription(),
                billedQuantity, displayUnit, amount, lo.intValue(), hi == null ? null : hi.intValue(),
                unitSize.intValue(), rounding.name()));
    }

    private List<PricingRule> rules(PricingPolicy policy, PricingCategory category, String profile, MediaPricingComponent component) {
        return policy.getRules().stream().filter(PricingRule::isEnabled).filter(r -> r.getCategory() == category)
                .filter(r -> profile == null || profile.equalsIgnoreCase(r.getPricingProfile()))
                .filter(r -> component == null || r.getMediaComponent() == component).toList();
    }

    private List<PricingRule> profileRules(PricingPolicy policy, PricingCategory category, String profile,
            MediaPricingComponent component) {
        List<PricingRule> configured = rules(policy, category, profile, component).stream()
                .filter(rule -> rule.getChecklistDefinitionId() == null).toList();
        if (!configured.isEmpty() || !GENERIC_CUSTOMER_ADDED_PROFILE.equals(profile)) return configured;
        return rules(policy, category, LEGACY_CUSTOM_PROFILE, component).stream()
                .filter(rule -> rule.getChecklistDefinitionId() == null).toList();
    }

    /** Checklist target wins; then the checklist profile; finally DEFAULT global rules. */
    private List<PricingRule> targetedProfileRules(PricingPolicy policy, PricingCategory category,
            String checklistId, String profile, MediaPricingComponent component) {
        if (checklistId != null) {
            List<PricingRule> targeted = rules(policy, category, null, component).stream()
                    .filter(rule -> checklistId.equals(rule.getChecklistDefinitionId())).toList();
            if (!targeted.isEmpty()) return targeted;
        }
        List<PricingRule> profileRules = profileRules(policy, category, profile, component);
        if (!profileRules.isEmpty() || DEFAULT_PROFILE.equals(profile)) return profileRules;
        return profileRules(policy, category, DEFAULT_PROFILE, component);
    }

    public static String normalizeProfile(String profile) {
        return profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile.trim().toUpperCase(Locale.ROOT);
    }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal positive(BigDecimal v) { return nz(v).max(BigDecimal.ZERO); }
    private static BigDecimal money(BigDecimal v) { return nz(v).setScale(0, RoundingMode.HALF_UP); }
}
