package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown;
import com.ondemandmonitoring.pricing.enums.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every number below is policy data written by the test, not a value the calculator knows. */
class ChecklistPriceCalculatorTest {
    ChecklistPriceCalculator calculator = new ChecklistPriceCalculator();

    // ---- helpers -------------------------------------------------------------------------------------------------
    PricingPolicy policy(PricingRule... rules) {
        PricingPolicy p = new PricingPolicy();
        p.setId("p1"); p.setPolicyVersion(7); p.setStatus(PricingPolicyStatus.ACTIVE); p.setCurrency("VND");
        p.replaceRules(List.of(rules));
        return p;
    }
    PricingRule media(String profile, MediaPricingComponent component, String price) {
        PricingRule r = new PricingRule();
        r.setCategory(PricingCategory.MEDIA); r.setPricingProfile(profile); r.setMediaComponent(component);
        r.setRuleKey(profile + ":" + component + ":" + price); r.setDescription(component.name());
        r.setCalculationType(PricingCalculationType.FIXED); r.setFixedAmount(new BigDecimal(price)); r.setEnabled(true);
        return r;
    }
    PricingRule tier(String profile, MediaPricingComponent component, String from, String to, String block, String price,
            PricingRounding rounding) {
        PricingRule r = media(profile, component, price);
        r.setRuleKey(profile + ":" + component + ":" + from); r.setCalculationType(PricingCalculationType.PER_BLOCK);
        r.setMinValue(new BigDecimal(from)); r.setMaxValue(to == null ? null : new BigDecimal(to));
        r.setUnitSize(new BigDecimal(block)); r.setRoundingMode(rounding);
        return r;
    }
    PricingRule difficulty(String key, String price) {
        PricingRule r = new PricingRule();
        r.setCategory(PricingCategory.CHECKLIST); r.setRuleKey(key); r.setDescription("difficulty " + key);
        r.setCalculationType(PricingCalculationType.FIXED); r.setFixedAmount(new BigDecimal(price)); r.setEnabled(true);
        return r;
    }
    PricingRule dimension(PricingCategory category, String profile, String block, String price) {
        PricingRule r = new PricingRule();
        r.setCategory(category); r.setPricingProfile(profile); r.setRuleKey(profile + ":" + category);
        r.setDescription(category.name()); r.setCalculationType(PricingCalculationType.PER_BLOCK);
        r.setMinValue(BigDecimal.ZERO); r.setUnitSize(new BigDecimal(block));
        r.setFixedAmount(new BigDecimal(price)); r.setRoundingMode(PricingRounding.CEILING); r.setEnabled(true);
        return r;
    }
    ChecklistPriceCalculator.Input catalog(String base, Integer includedImages, Integer includedVideos,
            Integer requestedImages, Integer requestedVideos) {
        return new ChecklistPriceCalculator.Input("c1", "Check roof", ChecklistDifficulty.BASIC, false, new BigDecimal(base),
                includedImages, includedVideos, requestedImages, requestedVideos, "STANDARD", null, true);
    }
    PricingPolicy illustrativePolicy() {
        return policy(
                media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"),
                media("STANDARD", MediaPricingComponent.INCLUDED_VIDEO, "120000"),
                tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000", PricingRounding.CEILING),
                tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "10", null, "10", "40000", PricingRounding.CEILING),
                tier("STANDARD", MediaPricingComponent.EXTRA_VIDEO, "0", null, "1", "90000", PricingRounding.CEILING));
    }

    // ---- tests ---------------------------------------------------------------------------------------------------
    @Test void thirtyImagesWithTenIncludedBillsIncludedBlockPlusTwoDifferentExtraTiers() {
        ChecklistPriceBreakdown result = calculator.price(illustrativePolicy(), catalog("200000", 10, 1, 30, null));

        assertThat(result.extraImageCount()).isEqualTo(20);
        assertThat(result.extraVideoCount()).isZero();
        // base 200k + included images 50k + included video 120k + first extra block 50k + second extra block 40k
        assertThat(result.subtotal()).isEqualByComparingTo("460000");
        assertThat(result.lines()).extracting(l -> l.component())
                .containsExactly("BASE", "INCLUDED_IMAGE", "INCLUDED_VIDEO", "EXTRA_IMAGE", "EXTRA_IMAGE");
        assertThat(result.lines().get(3).amount()).isEqualByComparingTo("50000");
        assertThat(result.lines().get(4).amount()).isEqualByComparingTo("40000");
    }

    @Test void singleFlatTierPricesEveryExtraBlockTheSame() {
        PricingPolicy flat = policy(tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "40000", PricingRounding.CEILING));
        ChecklistPriceBreakdown result = calculator.price(flat, catalog("0", 10, 0, 40, null));
        assertThat(result.subtotal()).isEqualByComparingTo("120000"); // 30 extra = 3 blocks x 40k
    }

    @Test void includedMediaIsNeverBilledThroughExtraTiersAndFewerImagesAreNotRefunded() {
        ChecklistPriceBreakdown atAllowance = calculator.price(illustrativePolicy(), catalog("0", 10, 1, 10, 1));
        ChecklistPriceBreakdown below = calculator.price(illustrativePolicy(), catalog("0", 10, 1, 3, 0));
        assertThat(atAllowance.extraImageCount()).isZero();
        assertThat(atAllowance.subtotal()).isEqualByComparingTo("170000"); // included 50k + 120k only
        assertThat(below.extraImageCount()).isZero();
        assertThat(below.subtotal()).isEqualByComparingTo(atAllowance.subtotal());
    }

    @Test void imagesAndVideosAreClassifiedIndependently() {
        ChecklistPriceBreakdown result = calculator.price(illustrativePolicy(), catalog("0", 10, 1, 10, 3));
        assertThat(result.extraImageCount()).isZero();
        assertThat(result.extraVideoCount()).isEqualTo(2);
        assertThat(result.subtotal()).isEqualByComparingTo("350000"); // 50k + 120k + 2 x 90k
    }

    @Test void roundingModeDecidesPartialBlocks() {
        for (var entry : List.of(
                java.util.Map.entry(PricingRounding.CEILING, "100000"),
                java.util.Map.entry(PricingRounding.FLOOR, "50000"),
                java.util.Map.entry(PricingRounding.HALF_UP, "100000"))) {
            PricingPolicy p = policy(tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "50000", entry.getKey()));
            // 15 extra images = 1.5 blocks
            assertThat(calculator.price(p, catalog("0", 0, 0, 15, null)).subtotal()).as(entry.getKey().name()).isEqualByComparingTo(entry.getValue());
        }
        PricingPolicy half = policy(tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "50000", PricingRounding.HALF_UP));
        assertThat(calculator.price(half, catalog("0", 0, 0, 14, null)).subtotal()).isEqualByComparingTo("50000");
    }

    @Test void extraMediaWithoutConfiguredTierIsAnErrorNotAFreeUpgrade() {
        PricingPolicy none = policy(media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        assertThatThrownBy(() -> calculator.price(none, catalog("0", 10, 0, 11, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED));
    }

    @Test void conflictMessagesTellTheAdministratorHowToFixIt() {
        PricingPolicy none = policy(media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        assertThatThrownBy(() -> calculator.price(none, catalog("0", 10, 0, 11, null)))
                .hasMessageContaining("profile STANDARD").hasMessageContaining("add a tier starting at 0").hasMessageContaining("publish");
        PricingPolicy bounded = policy(tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000", PricingRounding.CEILING));
        assertThatThrownBy(() -> calculator.price(bounded, catalog("0", 0, 0, 25, null)))
                .hasMessageContaining("only cover up to 10").hasMessageContaining("extend the tiers");
        assertThatThrownBy(() -> calculator.price(policy(), new ChecklistPriceCalculator.Input(null, "c", null, true, null, null, null, null, null, null, null, true)))
                .hasMessageContaining("administrator must publish");
    }

    @Test void conflictOnlyHappensWithoutAPolicyOrWithoutCoverageNeverForCoveredQuantities() {
        PricingPolicy p = illustrativePolicy();
        for (int images : new int[] {0, 10, 11, 20, 21, 500}) assertThat(calculator.price(p, catalog("0", 10, 1, images, null)).subtotal()).isNotNull();
        for (int videos : new int[] {0, 1, 2, 500}) assertThat(calculator.price(p, catalog("0", 10, 1, null, videos)).subtotal()).isNotNull();
    }

    @Test void tierGapOrUncoveredQuantityIsAnError() {
        PricingPolicy bounded = policy(tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000", PricingRounding.CEILING));
        assertThat(calculator.price(bounded, catalog("0", 0, 0, 10, null)).subtotal()).isEqualByComparingTo("50000");
        assertThatThrownBy(() -> calculator.price(bounded, catalog("0", 0, 0, 11, null))).isInstanceOf(ApiException.class);
        PricingPolicy gap = policy(
                tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000", PricingRounding.CEILING),
                tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "20", null, "10", "40000", PricingRounding.CEILING));
        assertThatThrownBy(() -> calculator.price(gap, catalog("0", 0, 0, 30, null))).isInstanceOf(ApiException.class);
    }

    @Test void disabledTierAndOtherProfilesAreIgnored() {
        PricingRule disabled = tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "1", PricingRounding.CEILING);
        disabled.setEnabled(false);
        PricingPolicy p = policy(disabled, tier("PREMIUM", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "999", PricingRounding.CEILING));
        assertThatThrownBy(() -> calculator.price(p, catalog("0", 0, 0, 10, null))).isInstanceOf(ApiException.class);
    }

    @Test void difficultyRuleIsAddedOnceAndCanBeLeftOut() {
        PricingPolicy p = policy(difficulty("HARD", "150000"));
        var input = new ChecklistPriceCalculator.Input("c1", "x", ChecklistDifficulty.HARD, false, BigDecimal.ZERO, 0, 0, null, null, null, null, true);
        assertThat(calculator.price(p, input).subtotal()).isEqualByComparingTo("150000");
        var without = new ChecklistPriceCalculator.Input("c1", "x", ChecklistDifficulty.HARD, false, BigDecimal.ZERO, 0, 0, null, null, null, null, false);
        assertThat(calculator.price(p, without).subtotal()).isZero();
    }

    @Test void customChecklistUsesCustomProfileRulesAndItsOwnAllowance() {
        PricingRule allowance = media("CUSTOM", MediaPricingComponent.INCLUDED_IMAGE, "0"); allowance.setIncludedQuantity(5);
        PricingPolicy p = policy(media("CUSTOM", MediaPricingComponent.CHECKLIST_BASE, "300000"), allowance,
                tier("DEFAULT", MediaPricingComponent.EXTRA_IMAGE, "0", null, "5", "70000", PricingRounding.CEILING));
        var input = new ChecklistPriceCalculator.Input(null, "Check antenna", null, true, null, null, null, 12, null, null, null, true);

        ChecklistPriceBreakdown result = calculator.price(p, input);

        assertThat(result.custom()).isTrue();
        assertThat(result.includedImageCount()).isEqualTo(5);
        assertThat(result.extraImageCount()).isEqualTo(7);
        assertThat(result.subtotal()).isEqualByComparingTo("440000"); // 300k + ceil(7/5)=2 blocks x 70k
    }

    @Test void customChecklistUsesTheSharedMediaPriceListNotOneOfItsOwn() {
        // A list left over on the customer-added profile is ignored: extra media has one price list.
        PricingPolicy p = policy(media("CUSTOM", MediaPricingComponent.CHECKLIST_BASE, "300000"),
                tier("CUSTOM", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "999999", PricingRounding.CEILING),
                tier("DEFAULT", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "50000", PricingRounding.CEILING));
        var custom = new ChecklistPriceCalculator.Input(null, "Check antenna", null, true, null, null, null, 10, null, null, null, true);
        var catalog = new ChecklistPriceCalculator.Input("c1", "Check roof", null, false, BigDecimal.ZERO, 0, 0, 10, null, "DEFAULT", null, true);
        // The same ten extra images cost the same on both; only the base price differs.
        assertThat(calculator.price(p, custom).subtotal().subtract(new BigDecimal("300000")))
                .isEqualByComparingTo(calculator.price(p, catalog).subtotal()).isEqualByComparingTo("50000");
    }

    @Test void customChecklistIsChargedOnlyForAreaAboveTheIncludedAllowance() {
        PricingRule allowance = media("CUSTOM", MediaPricingComponent.INCLUDED_AREA, "0"); allowance.setIncludedQuantity(5);
        PricingPolicy p = policy(media("CUSTOM", MediaPricingComponent.CHECKLIST_BASE, "200000"), allowance,
                media("CUSTOM", MediaPricingComponent.EXTRA_AREA, "50000"));
        BigDecimal small = new BigDecimal("4.2"), large = new BigDecimal("8.5");
        PricingContextHelper helper = new PricingContextHelper();
        assertThat(calculator.price(p, helper.custom(small)).subtotal()).isEqualByComparingTo("200000");
        var big = calculator.price(p, helper.custom(large));
        // 8.5 ha - 5 ha included = 3.5 ha x 50,000
        assertThat(big.subtotal()).isEqualByComparingTo("375000");
        assertThat(big.lines()).extracting(l -> l.component()).contains("EXTRA_AREA");
        assertThat(big.lines().getLast().description()).contains("3.5 ha");
    }

    @Test void customAreaSurchargeNeedsAPriceWhenTheAreaExceedsTheAllowance() {
        PricingRule allowance = media("CUSTOM", MediaPricingComponent.INCLUDED_AREA, "0"); allowance.setIncludedQuantity(5);
        PricingPolicy p = policy(media("CUSTOM", MediaPricingComponent.CHECKLIST_BASE, "200000"), allowance);
        assertThatThrownBy(() -> calculator.price(p, new PricingContextHelper().custom(new BigDecimal("9"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED));
        // Within the allowance nothing needs pricing.
        assertThat(calculator.price(p, new PricingContextHelper().custom(new BigDecimal("5"))).subtotal()).isEqualByComparingTo("200000");
    }

    /** Builds a customer-added checklist input for an order of a given area. */
    static class PricingContextHelper {
        ChecklistPriceCalculator.Input custom(BigDecimal areaHa) {
            return new ChecklistPriceCalculator.Input(null, "Check antenna", null, true, null, null, null, null, null, null, null, true,
                    ChecklistPriceCalculator.PricingDimensions.disabled(),
                    new ChecklistPriceCalculator.RequestContext(areaHa, BigDecimal.ZERO, BigDecimal.ZERO));
        }
    }

    @Test void sharedDimensionsAreChargedOnlyWhenTheChecklistEnablesThem() {
        PricingPolicy p = policy(dimension(PricingCategory.AREA, "STANDARD", "10", "100000"),
                dimension(PricingCategory.DISTANCE, "STANDARD", "1", "75000"));
        var context = new ChecklistPriceCalculator.RequestContext(new BigDecimal("50.3"), new BigDecimal("5.2"), BigDecimal.ZERO);
        var areaOnly = new ChecklistPriceCalculator.Input("area", "Roof", ChecklistDifficulty.BASIC, false,
                BigDecimal.ZERO, 0, 0, 0, 0, "STANDARD", null, false,
                new ChecklistPriceCalculator.PricingDimensions(true, false, false, new BigDecimal("10"), BigDecimal.ZERO,
                        BigDecimal.ZERO), context);
        var lengthOnly = new ChecklistPriceCalculator.Input("length", "Pipeline", ChecklistDifficulty.BASIC, false,
                BigDecimal.ZERO, 0, 0, 0, 0, "STANDARD", null, false,
                new ChecklistPriceCalculator.PricingDimensions(false, true, false, BigDecimal.ZERO, new BigDecimal("1"),
                        BigDecimal.ZERO), context);
        var fixedOnly = new ChecklistPriceCalculator.Input("fixed", "Equipment", ChecklistDifficulty.BASIC, false,
                BigDecimal.ZERO, 0, 0, 0, 0, "STANDARD", null, false,
                ChecklistPriceCalculator.PricingDimensions.disabled(), context);

        assertThat(calculator.price(p, areaOnly).subtotal()).isEqualByComparingTo("500000");
        assertThat(calculator.price(p, lengthOnly).subtotal()).isEqualByComparingTo("375000");
        assertThat(calculator.price(p, fixedOnly).subtotal()).isZero();
    }

    @Test void customChecklistWithoutACustomRuleIsQuotedByTheManagerNeverPricedByALegacyRuleOrZero() {
        PricingRule legacy = new PricingRule();
        legacy.setCategory(PricingCategory.RESOURCE); legacy.setRuleKey("CUSTOM_BASE"); legacy.setDescription("legacy");
        legacy.setCalculationType(PricingCalculationType.FIXED); legacy.setFixedAmount(new BigDecimal("2000000")); legacy.setEnabled(true);
        var input = new ChecklistPriceCalculator.Input(null, "Custom", null, true, null, null, null, null, null, null, null, true);
        // the old per-order RESOURCE rule is no longer a silent per-item price
        assertThat(calculator.hasCustomRule(policy(legacy))).isFalse();
        assertThatThrownBy(() -> calculator.price(policy(legacy), input)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_CHECKLIST_PRICING_NOT_CONFIGURED));
        var pending = calculator.managerQuoted("Custom", null, 3, 1);
        assertThat(pending.quotePending()).isTrue();
        assertThat(pending.lines()).isEmpty();
        assertThat(pending.subtotal()).isZero();
        var quoted = calculator.managerQuoted("Custom", new BigDecimal("450000"), 3, 1);
        assertThat(quoted.quotePending()).isFalse();
        assertThat(quoted.subtotal()).isEqualByComparingTo("450000");
        assertThat(quoted.lines()).extracting(l -> l.component()).containsExactly("MANAGER_QUOTE");
        assertThat(calculator.hasCustomRule(policy(media("CUSTOM", MediaPricingComponent.CHECKLIST_BASE, "300000")))).isTrue();
    }

    @Test void negativeOrExcessiveRequestedQuantitiesAreRejected() {
        assertThatThrownBy(() -> calculator.price(illustrativePolicy(), catalog("0", 10, 1, -1, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calculator.price(illustrativePolicy(), catalog("0", 10, 1, 501, null))).isInstanceOf(ApiException.class);
    }

    @Test void breakdownTotalsEqualTheSumOfItsLines() {
        ChecklistPriceBreakdown result = calculator.price(illustrativePolicy(), catalog("123000", 10, 1, 45, 4));
        BigDecimal sum = result.lines().stream().map(ChecklistPriceBreakdown.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.subtotal()).isEqualByComparingTo(sum);
        List<String> components = new ArrayList<>(result.lines().stream().map(l -> l.component()).toList());
        assertThat(components).filteredOn("INCLUDED_IMAGE"::equals).hasSize(1);
        assertThat(components).filteredOn("INCLUDED_VIDEO"::equals).hasSize(1);
    }

    @Test void checklistSpecificRulesOverrideProfileRulesAndRemainDifferentPerChecklist() {
        PricingRule profileImage = tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE,
                "0", null, "10", "30000", PricingRounding.CEILING);
        PricingRule checklistXImage = tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE,
                "0", null, "10", "50000", PricingRounding.CEILING);
        checklistXImage.setChecklistDefinitionId("c1");
        PricingRule checklistYImage = tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE,
                "0", null, "10", "70000", PricingRounding.CEILING);
        checklistYImage.setChecklistDefinitionId("c2");
        PricingPolicy configured = policy(profileImage, checklistXImage, checklistYImage);

        var x = calculator.price(configured, catalog("100000", 1, 1, 2, 1));
        var yInput = new ChecklistPriceCalculator.Input("c2", "Facade", ChecklistDifficulty.BASIC,
                false, new BigDecimal("100000"), 1, 1, 2, 1, "STANDARD", null, false);
        var y = calculator.price(configured, yInput);

        assertThat(x.subtotal()).isEqualByComparingTo("150000");
        assertThat(y.subtotal()).isEqualByComparingTo("170000");
    }

    @Test void checklistSpecificGraduatedVideoPricingChargesEachTierIncrementally() {
        PricingRule first = tier("STANDARD", MediaPricingComponent.EXTRA_VIDEO,
                "0", "1", "1", "50000", PricingRounding.CEILING);
        PricingRule following = tier("STANDARD", MediaPricingComponent.EXTRA_VIDEO,
                "1", null, "1", "40000", PricingRounding.CEILING);
        first.setChecklistDefinitionId("c1");
        following.setChecklistDefinitionId("c1");
        PricingPolicy configured = policy(first, following);

        assertThat(calculator.price(configured, catalog("0", 1, 1, 1, 2)).subtotal())
                .isEqualByComparingTo("50000");
        assertThat(calculator.price(configured, catalog("0", 1, 1, 1, 3)).subtotal())
                .isEqualByComparingTo("90000");
        assertThat(calculator.price(configured, catalog("0", 1, 1, 1, 4)).subtotal())
                .isEqualByComparingTo("130000");
    }

    @Test void configuredChecklistExampleProducesBasePlusFirstImageAndVideoTier() {
        PricingRule image = tier("STANDARD", MediaPricingComponent.EXTRA_IMAGE,
                "0", null, "10", "50000", PricingRounding.CEILING);
        PricingRule video = tier("STANDARD", MediaPricingComponent.EXTRA_VIDEO,
                "0", null, "1", "50000", PricingRounding.CEILING);
        image.setChecklistDefinitionId("c1");
        video.setChecklistDefinitionId("c1");

        ChecklistPriceBreakdown result = calculator.price(policy(image, video),
                catalog("100000", 1, 1, 2, 2));

        assertThat(result.extraImageCount()).isEqualTo(1);
        assertThat(result.extraVideoCount()).isEqualTo(1);
        assertThat(result.subtotal()).isEqualByComparingTo("200000");
        assertThat(result.lines()).extracting(ChecklistPriceBreakdown.Line::amount)
                .containsExactly(new BigDecimal("100000"), new BigDecimal("50000"), new BigDecimal("50000"));
    }
}
