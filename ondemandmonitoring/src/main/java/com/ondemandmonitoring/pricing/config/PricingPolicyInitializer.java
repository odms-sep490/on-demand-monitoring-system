package com.ondemandmonitoring.pricing.config;

import com.ondemandmonitoring.pricing.domain.*;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@org.springframework.core.annotation.Order(100) // before the checklist pricing seed (150) and the default-price backfill (200)
public class PricingPolicyInitializer implements ApplicationRunner {
    private final PricingPolicyRepository policies;

    @Override @Transactional
    public void run(ApplicationArguments args) {
        Optional<PricingPolicy> active = policies.findFirstByStatusOrderByPolicyVersionDesc(PricingPolicyStatus.ACTIVE);
        if (active.isPresent()) {
            repairLegacyMediaPricing(active.get());
            return;
        }
        PricingPolicy p = new PricingPolicy();
        p.setName("Chính sách giá mặc định"); p.setPolicyVersion(1); p.setStatus(PricingPolicyStatus.ACTIVE);
        p.setCurrency("VND"); p.setEffectiveFrom(Instant.now());
        p.setManagerMaxDiscountPercent(new BigDecimal("25")); p.setManagerMaxMarkupPercent(new BigDecimal("25"));
        p.setHighRiskDiscountPercent(new BigDecimal("10")); p.setMinimumPriceRatio(new BigDecimal("0.75"));
        p.setMinimumPrice(new BigDecimal("100000"));
        var rules = new ArrayList<PricingRule>(); int i = 0;
        rules.add(rule(PricingCategory.DISTANCE, "UP_TO_5", "Quãng đường đến 5 km", PricingCalculationType.FIXED, null, "5", "0", null, null, null, i++));
        rules.add(rule(PricingCategory.DISTANCE, "UP_TO_20", "Phụ phí quãng đường từ 5 đến 20 km", PricingCalculationType.PERCENT_OF_BASE, "5", "20", null, "10", null, null, i++));
        rules.add(rule(PricingCategory.DISTANCE, "UP_TO_50", "Phụ phí quãng đường từ 20 đến 50 km", PricingCalculationType.PERCENT_OF_BASE, "20", "50", null, "30", null, null, i++));
        rules.add(rule(PricingCategory.DISTANCE, "UP_TO_100", "Chi phí nguồn lực cho quãng đường dài", PricingCalculationType.RESOURCE_BASED, "50", "100", "2400000", null, null, null, i++));
        rules.add(rule(PricingCategory.DISTANCE, "OVER_100", "Quãng đường trên 100 km cần đánh giá riêng", PricingCalculationType.MANUAL_QUOTE, "100", null, null, null, null, null, i++));
        rules.add(rule(PricingCategory.AREA, "UP_TO_10", "Diện tích đến 10 ha", PricingCalculationType.FIXED, null, "10", "0", null, null, null, i++));
        rules.add(rule(PricingCategory.AREA, "UP_TO_50", "Phụ phí theo mỗi 10 ha", PricingCalculationType.PER_BLOCK, "10", "50", "100000", null, "10", null, i++));
        PricingRule largeArea = rule(PricingCategory.AREA, "UP_TO_200", "Phụ phí diện tích lớn theo mỗi 10 ha", PricingCalculationType.TIERED_BLOCK, "50", "200", "80000", null, "10", null, i++);
        largeArea.setBaseAmount(new BigDecimal("400000")); rules.add(largeArea);
        rules.add(rule(PricingCategory.AREA, "OVER_200", "Diện tích trên 200 ha cần đánh giá riêng", PricingCalculationType.MANUAL_QUOTE, "200", null, null, null, null, null, i++));
        rules.add(fixed(PricingCategory.CHECKLIST, "BASIC", "Hạng mục cơ bản", "0", i++));
        rules.add(fixed(PricingCategory.CHECKLIST, "MEDIUM", "Hạng mục độ khó trung bình", "50000", i++));
        rules.add(fixed(PricingCategory.CHECKLIST, "HARD", "Hạng mục khó", "150000", i++));
        rules.add(fixed(PricingCategory.CHECKLIST, "VERY_HARD", "Hạng mục rất khó", "400000", i++));
        rules.add(fixed(PricingCategory.OUTPUT, "VIDEO", "Video bàn giao", "300000", i++));
        rules.add(fixed(PricingCategory.OUTPUT, "PDF_REPORT", "Báo cáo PDF theo yêu cầu", "200000", i++));
        rules.add(fixed(PricingCategory.OUTPUT, "ORTHOMOSAIC", "Ảnh trực giao ghép theo yêu cầu", "500000", i++));
        rules.add(fixed(PricingCategory.OUTPUT, "MODEL_3D", "Mô hình 3D theo yêu cầu", "800000", i++));
        rules.add(rule(PricingCategory.OUTPUT, "HIGH_RESOLUTION_IMAGE", "Mỗi 10 ảnh độ phân giải cao", PricingCalculationType.PER_BLOCK, "0", null, "100000", null, "10", null, i++));
        rules.add(rule(PricingCategory.OUTPUT, "4K_VIDEO", "Mỗi video 4K theo yêu cầu", PricingCalculationType.PER_UNIT, null, null, "150000", null, null, null, i++));
        rules.add(fixed(PricingCategory.OUTPUT, "THERMAL", "Dữ liệu ảnh nhiệt", "500000", i++));
        rules.add(fixed(PricingCategory.OUTPUT, "MULTISPECTRAL", "Dữ liệu đa phổ", "700000", i++));
        rules.add(fixed(PricingCategory.RESOURCE, "CUSTOM_BASE", "Chi phí cơ sở cho công việc tùy chỉnh", "2000000", i++));
        rules.add(fixed(PricingCategory.RESOURCE, "PILOT", "Nhân sự điều khiển", "800000", i++));
        rules.add(fixed(PricingCategory.RESOURCE, "DRONE", "Thiết bị bay", "600000", i++));
        rules.add(fixed(PricingCategory.RESOURCE, "BATTERY", "Pin thiết bị bay", "100000", i++));
        rules.add(multiplier("FLAT", "Địa hình bằng phẳng", "1.0", i++)); rules.add(multiplier("MOUNTAIN", "Điều chỉnh do địa hình đồi núi", "1.2", i++));
        rules.add(multiplier("HIGH_RISE", "Điều chỉnh do khu vực nhà cao tầng", "1.2", i++)); rules.add(multiplier("DAM_RESERVOIR", "Điều chỉnh do khu vực đập hoặc hồ chứa", "1.3", i++));
        rules.add(multiplier("HARD_TO_ACCESS", "Điều chỉnh do khu vực khó tiếp cận", "1.3", i++));
        rules.add(rule(PricingCategory.CONDITION, "URGENT", "Yêu cầu thực hiện khẩn cấp", PricingCalculationType.PERCENT_OF_BASE, null, null, null, "15", null, null, i++));
        rules.add(fixed(PricingCategory.PERMIT, "REQUIRED", "Chi phí giấy phép hoặc vận hành đặc biệt", "1000000", i++));
        // Starter MEDIA profiles for a fresh installation. They are ordinary policy data: an Admin edits them in a new policy version.
        rules.add(media("DEFAULT", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000", i++));
        rules.add(media("DEFAULT", MediaPricingComponent.EXTRA_IMAGE, "10", null, "10", "40000", i++));
        rules.add(media("DEFAULT", MediaPricingComponent.EXTRA_VIDEO, "0", null, "1", "100000", i++));
        rules.add(media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.CHECKLIST_BASE, null, null, null, "200000", i++));
        PricingRule customImages = media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "0", i++);
        customImages.setIncludedQuantity(5); rules.add(customImages);
        PricingRule customArea = media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_AREA, null, null, null, "0", i++);
        customArea.setIncludedQuantity(15); rules.add(customArea);
        rules.add(media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.EXTRA_AREA, null, null, null, "10000", i));
        p.replaceRules(rules); policies.save(p);
    }

    /**
     * Policies created before checklist media pricing was introduced have no MEDIA rows. Leaving
     * such a policy active makes an Admin's checklist allowance look valid until the first image
     * above that allowance is requested. Published policies stay immutable: migrate by publishing
     * a new system-generated version instead of editing the historical version in place.
     */
    private void repairLegacyMediaPricing(PricingPolicy active) {
        List<RequiredMedia> missing = requiredMediaRules().stream()
                .filter(required -> active.getRules().stream().noneMatch(rule -> rule.isEnabled()
                        && rule.getCategory() == PricingCategory.MEDIA
                        && required.profile().equalsIgnoreCase(rule.getPricingProfile())
                        && rule.getMediaComponent() == required.component()))
                .toList();
        if (missing.isEmpty()) return;

        Instant now = Instant.now();
        PricingPolicy repaired = copyPolicy(active);
        repaired.setPolicyVersion(policies.findAllByOrderByPolicyVersionDesc().stream()
                .mapToInt(PricingPolicy::getPolicyVersion).max().orElse(active.getPolicyVersion()) + 1);
        repaired.setStatus(PricingPolicyStatus.ACTIVE);
        repaired.setEffectiveFrom(now);
        repaired.setEffectiveTo(null);

        List<PricingRule> rules = active.getRules().stream().map(this::copyRule).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        rules.removeIf(rule -> rule.getCategory() == PricingCategory.MEDIA
                && missing.stream().anyMatch(required -> required.profile().equalsIgnoreCase(rule.getPricingProfile())
                        && required.component() == rule.getMediaComponent()));
        int priority = rules.stream().mapToInt(PricingRule::getPriority).max().orElse(-1) + 1;
        if (missing.contains(new RequiredMedia(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_IMAGE))) {
            rules.add(media(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_IMAGE,
                    "0", "10", "10", "50000", priority++));
            rules.add(media(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_IMAGE,
                    "10", null, "10", "40000", priority++));
        }
        if (missing.contains(new RequiredMedia(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_VIDEO))) {
            rules.add(media(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_VIDEO,
                    "0", null, "1", "100000", priority++));
        }
        if (missing.contains(new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.CHECKLIST_BASE))) {
            rules.add(media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.CHECKLIST_BASE,
                    null, null, null, "200000", priority++));
        }
        if (missing.contains(new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_IMAGE))) {
            PricingRule included = media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_IMAGE,
                    null, null, null, "0", priority++);
            included.setIncludedQuantity(5);
            rules.add(included);
        }
        if (missing.contains(new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_AREA))) {
            PricingRule area = media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_AREA,
                    null, null, null, "0", priority++);
            area.setIncludedQuantity(15);
            rules.add(area);
        }
        if (missing.contains(new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.EXTRA_AREA))) {
            rules.add(media(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.EXTRA_AREA,
                    null, null, null, "10000", priority));
        }
        repaired.replaceRules(rules);

        active.setStatus(PricingPolicyStatus.SUPERSEDED);
        active.setEffectiveTo(now);
        policies.save(active);
        policies.save(repaired);
    }

    private List<RequiredMedia> requiredMediaRules() {
        return List.of(
                new RequiredMedia(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_IMAGE),
                new RequiredMedia(ChecklistPriceCalculator.DEFAULT_PROFILE, MediaPricingComponent.EXTRA_VIDEO),
                new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.CHECKLIST_BASE),
                new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_IMAGE),
                new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.INCLUDED_AREA),
                new RequiredMedia(ChecklistPriceCalculator.GENERIC_CUSTOMER_ADDED_PROFILE, MediaPricingComponent.EXTRA_AREA));
    }

    private record RequiredMedia(String profile, MediaPricingComponent component) {}

    private PricingPolicy copyPolicy(PricingPolicy source) {
        PricingPolicy copy = new PricingPolicy();
        copy.setName(source.getName());
        copy.setCurrency(source.getCurrency());
        copy.setManagerMaxDiscountPercent(source.getManagerMaxDiscountPercent());
        copy.setManagerMaxMarkupPercent(source.getManagerMaxMarkupPercent());
        copy.setHighRiskDiscountPercent(source.getHighRiskDiscountPercent());
        copy.setMinimumPriceRatio(source.getMinimumPriceRatio());
        copy.setMinimumPrice(source.getMinimumPrice());
        return copy;
    }

    private PricingRule copyRule(PricingRule source) {
        PricingRule copy = new PricingRule();
        copy.setCategory(source.getCategory());
        copy.setCalculationType(source.getCalculationType());
        copy.setRuleKey(source.getRuleKey());
        copy.setDescription(source.getDescription());
        copy.setMinValue(source.getMinValue());
        copy.setMaxValue(source.getMaxValue());
        copy.setFixedAmount(source.getFixedAmount());
        copy.setBaseAmount(source.getBaseAmount());
        copy.setPercentage(source.getPercentage());
        copy.setUnitSize(source.getUnitSize());
        copy.setMultiplier(source.getMultiplier());
        copy.setPricingProfile(source.getPricingProfile());
        copy.setMediaComponent(source.getMediaComponent());
        copy.setIncludedQuantity(source.getIncludedQuantity());
        copy.setRoundingMode(source.getRoundingMode());
        copy.setPriority(source.getPriority());
        copy.setEnabled(source.isEnabled());
        return copy;
    }

    private PricingRule media(String profile, MediaPricingComponent component, String min, String max, String block, String price, int priority) {
        boolean tier = component == MediaPricingComponent.EXTRA_IMAGE || component == MediaPricingComponent.EXTRA_VIDEO;
        PricingRule r = rule(PricingCategory.MEDIA, profile + ":" + component + (min == null ? "" : ":" + min), profile + " · " + component,
                tier ? PricingCalculationType.PER_BLOCK : PricingCalculationType.FIXED, min, max, price, null, block, null, priority);
        r.setPricingProfile(profile); r.setMediaComponent(component);
        if (tier) r.setRoundingMode(PricingRounding.CEILING);
        return r;
    }
    private PricingRule fixed(PricingCategory c, String key, String description, String amount, int priority) {
        return rule(c, key, description, PricingCalculationType.FIXED, null, null, amount, null, null, null, priority);
    }
    private PricingRule multiplier(String key, String description, String value, int priority) {
        return rule(PricingCategory.TERRAIN, key, description, PricingCalculationType.MULTIPLIER, null, null, null, null, null, value, priority);
    }
    private PricingRule rule(PricingCategory c, String key, String description, PricingCalculationType type,
            String min, String max, String fixed, String percent, String unit, String multiplier, int priority) {
        PricingRule r = new PricingRule(); r.setCategory(c); r.setRuleKey(key); r.setDescription(description);
        r.setCalculationType(type); r.setMinValue(bd(min)); r.setMaxValue(bd(max)); r.setFixedAmount(bd(fixed));
        r.setPercentage(bd(percent)); r.setUnitSize(bd(unit)); r.setMultiplier(bd(multiplier)); r.setPriority(priority); r.setEnabled(true); return r;
    }
    private BigDecimal bd(String value) { return value == null ? null : new BigDecimal(value); }
}
