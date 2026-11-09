package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderDeliverable;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.dto.PricingBreakdownItem;
import com.ondemandmonitoring.pricing.enums.PricingCalculationType;
import com.ondemandmonitoring.pricing.enums.PricingCategory;
import com.ondemandmonitoring.pricing.enums.TerrainType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Canonical calculator for charges that belong to an order rather than to an individual checklist.
 * Both the customer preview and the persisted-order pricing engine call this component.
 */
@Component
public class OrderLevelPriceCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(0);

    public record Context(List<OrderResultFormat> resultFormats,
            Map<OrderResultFormat, BigDecimal> outputQuantities,
            BigDecimal highResolutionImageQuantity, BigDecimal highResolutionVideoQuantity,
            boolean permitRequired, TerrainType terrain) {
        public Context {
            resultFormats = resultFormats == null ? List.of() : List.copyOf(new LinkedHashSet<>(resultFormats));
            outputQuantities = outputQuantities == null ? Map.of() : Map.copyOf(outputQuantities);
            highResolutionImageQuantity = nz(highResolutionImageQuantity);
            highResolutionVideoQuantity = nz(highResolutionVideoQuantity);
            terrain = terrain == null ? TerrainType.FLAT : terrain;
        }

        public static Context preview(List<OrderResultFormat> formats, BigDecimal images, BigDecimal videos,
                OrderPermitStatus permitStatus) {
            Map<OrderResultFormat, BigDecimal> quantities = new EnumMap<>(OrderResultFormat.class);
            quantities.put(OrderResultFormat.PHOTO, nz(images));
            quantities.put(OrderResultFormat.VIDEO, nz(videos));
            return new Context(formats, quantities, ZERO, ZERO,
                    permitStatus != null && permitStatus != OrderPermitStatus.NONE, TerrainType.FLAT);
        }

        public static Context order(Order order) {
            Map<OrderResultFormat, BigDecimal> quantities = new EnumMap<>(OrderResultFormat.class);
            quantities.put(OrderResultFormat.PHOTO, requestedOutputQuantity(order, "IMAGE"));
            quantities.put(OrderResultFormat.VIDEO, requestedOutputQuantity(order, "VIDEO"));
            return new Context(order.getResultFormats(), quantities,
                    requestedQualityQuantity(order, "IMAGE", "4K"),
                    requestedQualityQuantity(order, "VIDEO", "4K"),
                    Boolean.TRUE.equals(order.getFlightCompliance().getPermitRequired()) || order.getFlightCompliance().getPermitStatus() == OrderPermitStatus.NEED_SUPPORT,
                    TerrainType.FLAT);
        }
    }

    /** Stable public key plus the canonical manager breakdown line. */
    public record Charge(String key, PricingBreakdownItem line) {}

    public List<Charge> calculate(PricingPolicy policy, Context context, BigDecimal startingSubtotal,
            BigDecimal percentageBase) {
        List<Charge> result = new ArrayList<>();
        BigDecimal runningTotal = money(startingSubtotal);
        BigDecimal base = money(percentageBase);

        for (OrderResultFormat format : context.resultFormats()) {
            PricingRule rule = keyed(policy, PricingCategory.OUTPUT, format.name()).orElse(null);
            if (rule == null) continue;
            BigDecimal quantity = context.outputQuantities().getOrDefault(format, BigDecimal.ONE);
            if (quantity.signum() <= 0) quantity = BigDecimal.ONE;
            Charge charge = charge("OUTPUT_" + format.name(), rule, quantity, base);
            result.add(charge);
            runningTotal = runningTotal.add(charge.line().amount());
        }
        if (context.highResolutionImageQuantity().signum() > 0) {
            PricingRule rule = keyed(policy, PricingCategory.OUTPUT, "HIGH_RESOLUTION_IMAGE").orElse(null);
            if (rule != null) {
                Charge charge = charge("OUTPUT_HIGH_RESOLUTION_IMAGE", rule,
                        context.highResolutionImageQuantity(), base);
                result.add(charge); runningTotal = runningTotal.add(charge.line().amount());
            }
        }
        if (context.highResolutionVideoQuantity().signum() > 0) {
            PricingRule rule = keyed(policy, PricingCategory.OUTPUT, "4K_VIDEO").orElse(null);
            if (rule != null) {
                Charge charge = charge("OUTPUT_4K_VIDEO", rule, context.highResolutionVideoQuantity(), base);
                result.add(charge); runningTotal = runningTotal.add(charge.line().amount());
            }
        }
        if (context.permitRequired()) {
            PricingRule rule = keyed(policy, PricingCategory.PERMIT, "REQUIRED").orElse(null);
            if (rule != null) {
                Charge charge = charge("PERMIT_REQUIRED", rule, BigDecimal.ONE, base);
                result.add(charge); runningTotal = runningTotal.add(charge.line().amount());
            }
        }
        PricingRule terrain = keyed(policy, PricingCategory.TERRAIN, context.terrain().name()).orElse(null);
        if (terrain != null) {
            Charge charge = charge("TERRAIN_" + context.terrain().name(), terrain, BigDecimal.ONE, runningTotal);
            if (charge.line().amount().signum() != 0) {
                result.add(charge); runningTotal = runningTotal.add(charge.line().amount());
            }
        }
        for (PricingRule discount : enabled(policy, PricingCategory.DISCOUNT)) {
            Charge charge = charge("DISCOUNT_" + discount.getRuleKey(), discount, BigDecimal.ONE, runningTotal);
            if (charge.line().amount().signum() != 0) {
                result.add(charge); runningTotal = runningTotal.add(charge.line().amount());
            }
        }
        return List.copyOf(result);
    }

    private Charge charge(String key, PricingRule rule, BigDecimal value, BigDecimal base) {
        if (rule.getCalculationType() == PricingCalculationType.MANUAL_QUOTE)
            throw new ApiException(ErrorCode.CUSTOM_WORK_PRICING_INCOMPLETE, rule.getDescription());
        BigDecimal quantity = BigDecimal.ONE;
        BigDecimal amount;
        switch (rule.getCalculationType()) {
            case FIXED, ACTUAL_COST, RESOURCE_BASED -> amount = nz(rule.getFixedAmount());
            case PERCENT_OF_BASE -> amount = base.multiply(nz(rule.getPercentage()))
                    .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
            case PER_UNIT -> { quantity = value; amount = nz(rule.getFixedAmount()).multiply(value); }
            case PER_BLOCK, TIERED_BLOCK -> {
                BigDecimal chargeable = value.subtract(nz(rule.getMinValue())).max(BigDecimal.ZERO);
                quantity = chargeable.divide(positive(rule.getUnitSize()), 0, RoundingMode.CEILING);
                amount = nz(rule.getFixedAmount()).multiply(quantity);
                if (rule.getCalculationType() == PricingCalculationType.TIERED_BLOCK)
                    amount = amount.add(nz(rule.getBaseAmount()));
            }
            case MULTIPLIER -> amount = base.multiply(nz(rule.getMultiplier()).subtract(BigDecimal.ONE))
                    .setScale(0, RoundingMode.HALF_UP);
            default -> throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED);
        }
        if (rule.getCategory() == PricingCategory.DISCOUNT && amount.signum() > 0) amount = amount.negate();
        amount = money(amount);
        BigDecimal unit = quantity.signum() == 0 ? ZERO : amount.divide(quantity, 0, RoundingMode.HALF_UP);
        return new Charge(key, new PricingBreakdownItem(rule.getCategory(), rule.getDescription(), quantity, unit, amount));
    }

    private Optional<PricingRule> keyed(PricingPolicy policy, PricingCategory category, String key) {
        return enabled(policy, category).stream().filter(rule -> rule.getRuleKey().equalsIgnoreCase(key)).findFirst();
    }

    private List<PricingRule> enabled(PricingPolicy policy, PricingCategory category) {
        return policy.getRules().stream().filter(PricingRule::isEnabled)
                .filter(rule -> rule.getCategory() == category).toList();
    }

    private static BigDecimal requestedOutputQuantity(Order order, String mediaType) {
        BigDecimal quantity = order.getDeliverables().stream().map(OrderDeliverable::getRequirement)
                .filter(Objects::nonNull).map(map -> mediaQuantity(map, mediaType))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return quantity.signum() > 0 ? quantity : BigDecimal.ONE;
    }

    private static BigDecimal requestedQualityQuantity(Order order, String mediaType, String resolution) {
        return order.getDeliverables().stream().map(OrderDeliverable::getRequirement).filter(Objects::nonNull)
                .filter(map -> resolution.equalsIgnoreCase(String.valueOf(map.get("resolution"))))
                .map(map -> mediaQuantity(map, mediaType)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal mediaQuantity(Map<String, Object> requirement, String mediaType) {
        String key = "IMAGE".equals(mediaType) ? "imageQuantity" : "videoQuantity";
        if (requirement.containsKey("imageQuantity") || requirement.containsKey("videoQuantity"))
            return number(requirement.get(key));
        return mediaType.equalsIgnoreCase(String.valueOf(requirement.get("mediaType")))
                ? number(requirement.get("quantity")) : BigDecimal.ZERO;
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal positive(BigDecimal value) { return value == null || value.signum() <= 0 ? BigDecimal.ONE : value; }
    private static BigDecimal money(BigDecimal value) { return nz(value).setScale(0, RoundingMode.HALF_UP); }
}
