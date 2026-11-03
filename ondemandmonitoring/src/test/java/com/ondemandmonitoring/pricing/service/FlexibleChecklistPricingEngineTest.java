package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.CustomWorkAssessmentRepository;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.junit.jupiter.api.Test;

/** The engine prices a snapshot from its own frozen fields plus the policy; the live catalog is never consulted. */
class FlexibleChecklistPricingEngineTest {
    PricingPolicyRepository policies = mock(PricingPolicyRepository.class);
    CustomWorkAssessmentRepository assessments = mock(CustomWorkAssessmentRepository.class);
    RuleBasedPricingEngine engine = new RuleBasedPricingEngine(policies, assessments, new ChecklistPriceCalculator());

    PricingRule rule(PricingCategory category, String key, PricingCalculationType type, String price) {
        PricingRule r = new PricingRule();
        r.setCategory(category); r.setRuleKey(key); r.setDescription(key); r.setCalculationType(type);
        r.setFixedAmount(new BigDecimal(price)); r.setEnabled(true);
        return r;
    }
    PricingRule media(String profile, MediaPricingComponent component, String price) {
        PricingRule r = rule(PricingCategory.MEDIA, profile + ":" + component, PricingCalculationType.FIXED, price);
        r.setPricingProfile(profile); r.setMediaComponent(component);
        return r;
    }
    PricingRule tier(MediaPricingComponent component, String from, String to, String price) {
        PricingRule r = media("STANDARD", component, price);
        r.setRuleKey("STANDARD:" + component + ":" + from); r.setCalculationType(PricingCalculationType.PER_BLOCK);
        r.setMinValue(new BigDecimal(from)); r.setMaxValue(to == null ? null : new BigDecimal(to)); r.setUnitSize(BigDecimal.TEN);
        return r;
    }
    PricingPolicy policy(int version, PricingRule... rules) {
        PricingPolicy p = new PricingPolicy();
        p.setId("p" + version); p.setPolicyVersion(version); p.setStatus(PricingPolicyStatus.ACTIVE); p.setCurrency("VND");
        p.replaceRules(List.of(rules));
        when(policies.findEffectiveActive(any(Instant.class))).thenReturn(List.of(p));
        return p;
    }
    Order order(String packageFee) {
        var service = new com.ondemandmonitoring.service.domain.Service();
        service.setName("Inspection"); service.setBasePrice(new BigDecimal(packageFee));
        Order o = new Order();
        o.setId("o1"); o.setService(service);
        when(assessments.findByOrderId("o1")).thenReturn(Optional.empty());
        return o;
    }
    OrderChecklistItem snapshot(String base, int includedImages, int includedVideos, int requestedImages, int requestedVideos) {
        OrderChecklistItem item = new OrderChecklistItem();
        item.setContent("Check roof"); item.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE);
        item.setReviewStatus(ChecklistReviewStatus.INCLUDED); item.setDifficultySnapshot(ChecklistDifficulty.BASIC);
        item.setPricingSnapshotVersion(1); item.setBasePriceSnapshot(new BigDecimal(base));
        item.setIncludedImageCountSnapshot(includedImages); item.setIncludedVideoCountSnapshot(includedVideos);
        item.setRequestedImageCount(requestedImages); item.setRequestedVideoCount(requestedVideos);
        item.setPricingProfileSnapshot("STANDARD");
        return item;
    }

    @Test void orderTotalUsesChecklistAndMediaPricingWithoutAddingTheServiceBasePrice() {
        policy(4, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"),
                tier(MediaPricingComponent.EXTRA_IMAGE, "0", "10", "50000"), tier(MediaPricingComponent.EXTRA_IMAGE, "10", null, "40000"));

        var result = engine.calculate(order("1000000"), List.of(snapshot("200000", 10, 0, 30, 0)));

        // 200,000 checklist base + 50,000 included images + 50,000 + 40,000 extra tiers
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("340000");
        assertThat(result.policyVersion()).isEqualTo(4);
        assertThat(result.items()).extracting(i -> i.category()).containsExactly(
                PricingCategory.CHECKLIST, PricingCategory.MEDIA, PricingCategory.MEDIA, PricingCategory.MEDIA);
    }

    @Test void removingAnOptionalChecklistLowersThePriceByExactlyItsContribution() {
        policy(4, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        var both = engine.calculate(order("1000000"), List.of(snapshot("200000", 10, 0, 10, 0), snapshot("100000", 0, 0, 0, 0)));
        var one = engine.calculate(order("1000000"), List.of(snapshot("200000", 10, 0, 10, 0)));
        assertThat(both.systemCalculatedAmount().subtract(one.systemCalculatedAmount())).isEqualByComparingTo("100000");
    }

    @Test void rejectedAndDuplicateChecklistsAreNotBilled() {
        policy(4);
        OrderChecklistItem rejected = snapshot("200000", 0, 0, 0, 0);
        rejected.setReviewStatus(ChecklistReviewStatus.REJECTED);
        assertThat(engine.calculate(order("1000000"), List.of(rejected)).systemCalculatedAmount()).isEqualByComparingTo("0");
    }

    @Test void snapshotPriceIgnoresLaterCatalogEdits() {
        policy(4, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        ChecklistDefinition definition = new ChecklistDefinition();
        definition.setId("c1"); definition.setBasePrice(new BigDecimal("200000")); definition.setIncludedImageCount(10);
        OrderChecklistItem item = snapshot("200000", 10, 0, 10, 0);
        item.setSourceChecklist(definition);

        BigDecimal before = engine.calculate(order("1000000"), List.of(item)).systemCalculatedAmount();
        definition.setBasePrice(new BigDecimal("999999")); definition.setIncludedImageCount(500); definition.setPricingProfile("OTHER");

        assertThat(engine.calculate(order("1000000"), List.of(item)).systemCalculatedAmount()).isEqualByComparingTo(before);
    }

    @Test void legacyVersionZeroSnapshotsKeepDifficultyOnlyPricing() {
        policy(2, rule(PricingCategory.CHECKLIST, "HARD", PricingCalculationType.FIXED, "150000"),
                media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        OrderChecklistItem legacy = new OrderChecklistItem();
        legacy.setContent("old"); legacy.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE);
        legacy.setReviewStatus(ChecklistReviewStatus.INCLUDED); legacy.setDifficultySnapshot(ChecklistDifficulty.HARD);
        // pricingSnapshotVersion defaults to 0 and the media columns hold their zero defaults
        assertThat(engine.calculate(order("1000000"), List.of(legacy)).systemCalculatedAmount()).isEqualByComparingTo("1150000");
    }

    @Test void newPolicyVersionPricesNewSnapshotsDifferentlyWithoutTouchingTheOldTotals() {
        OrderChecklistItem item = snapshot("0", 10, 0, 10, 0);
        policy(1, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        var v1 = engine.calculate(order("1000000"), List.of(item));
        policy(2, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "80000"));
        var v2 = engine.calculate(order("1000000"), List.of(item));
        // The persisted Quote keeps the v1 numbers; recalculation is an explicit new-version act and reports its policy version.
        assertThat(v1.systemCalculatedAmount()).isEqualByComparingTo("50000");
        assertThat(v1.policyVersion()).isEqualTo(1);
        assertThat(v2.systemCalculatedAmount()).isEqualByComparingTo("80000");
        assertThat(v2.policyVersion()).isEqualTo(2);
    }

    @Test void historicalCustomSnapshotIsPricedByItsFrozenLegacyProfile() {
        OrderChecklistItem custom = new OrderChecklistItem();
        custom.setId("custom-1");
        custom.setContent("Check antenna"); custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setReviewStatus(ChecklistReviewStatus.ADDITIONAL); custom.setDifficultySnapshot(ChecklistDifficulty.BASIC);
        custom.setPricingSnapshotVersion(1); custom.setPricingProfileSnapshot("CUSTOM");

        policy(1, media("CUSTOM", MediaPricingComponent.CHECKLIST_BASE, "300000"));
        var priced = engine.calculate(order("0"), List.of(custom));
        assertThat(priced.systemCalculatedAmount()).isEqualByComparingTo("300000");
        assertThat(priced.pendingQuoteItems()).isEmpty();
        assertThat(priced.customWork()).isTrue();
    }

    @Test void managerQuotedItemsAreStillSkippedWhenRejectedAndDoNotAffectCatalogItems() {
        policy(1, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        OrderChecklistItem custom = new OrderChecklistItem();
        custom.setContent("Extra"); custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setReviewStatus(ChecklistReviewStatus.REJECTED); custom.setDifficultySnapshot(ChecklistDifficulty.BASIC);
        custom.setPricingSnapshotVersion(1); custom.setManagerQuoteRequired(true);
        var result = engine.calculate(order("0"), List.of(snapshot("200000", 10, 0, 10, 0), custom));
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("250000");
        assertThat(result.pendingQuoteItems()).isEmpty();
    }

    @Test void unpriceableExtraMediaFailsInsteadOfBeingFree() {
        policy(1, media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, "50000"));
        assertThatThrownBy(() -> engine.calculate(order("1000000"), List.of(snapshot("0", 10, 0, 11, 0)))).isInstanceOf(ApiException.class);
    }

    @Test void newOrderHasNoPackageLineBecauseTheChecklistsAreTheWholePrice() {
        policy(4);
        Order o = order("1000000");
        var result = engine.calculate(o, List.of(snapshot("200000", 0, 0, 0, 0)));
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("200000");
        assertThat(result.items()).extracting(i -> i.category()).doesNotContain(PricingCategory.PACKAGE);
    }

    @Test void highResolutionOutputUsesThePerTypeQuantitiesFromTheChecklists() {
        policy(4, rule(PricingCategory.OUTPUT, "4K_VIDEO", PricingCalculationType.PER_UNIT, "150000"));
        Order o = order("0");
        o.setDeliverables(new java.util.ArrayList<>(List.of(com.ondemandmonitoring.order.domain.OrderDeliverable.builder()
                .requirement(java.util.Map.of("mediaType", "IMAGE", "quantity", 10, "resolution", "4K",
                        "imageQuantity", 10, "videoQuantity", 2)).build())));
        var result = engine.calculate(o, List.of(snapshot("0", 0, 0, 0, 0)));
        // 2 requested videos at 4K, although the single mediaType/quantity pair says IMAGE
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("300000");
    }

    @Test void publishedAreaMediaRulesProduceExplicitFourHundredThenSixHundredThousandBreakdowns() {
        PricingRule area100 = rule(PricingCategory.AREA, "AREA", PricingCalculationType.PER_BLOCK, "100000");
        area100.setMinValue(BigDecimal.TEN); area100.setMaxValue(new BigDecimal("50")); area100.setUnitSize(BigDecimal.TEN);
        PricingRule image = tier(MediaPricingComponent.EXTRA_IMAGE, "0", null, "50000");
        PricingRule video = tier(MediaPricingComponent.EXTRA_VIDEO, "0", null, "50000");
        video.setUnitSize(BigDecimal.ONE);
        policy(1, area100, image, video);

        Order pricedOrder = order("0");
        double widthDegrees = 500d / 111_320d;
        double heightDegrees = 600d / 110_540d;
        pricedOrder.setTargetArea(new GeometryFactory().createPolygon(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(widthDegrees, 0),
                new Coordinate(widthDegrees, heightDegrees), new Coordinate(0, heightDegrees),
                new Coordinate(0, 0)
        }));
        OrderChecklistItem item = snapshot("100000", 1, 1, 2, 2);
        item.setId("item-1"); item.setAreaPricingEnabledSnapshot(true);
        item.setIncludedAreaHaSnapshot(new BigDecimal("10"));

        var first = engine.calculate(pricedOrder, List.of(item));

        assertThat(first.systemCalculatedAmount()).isEqualByComparingTo("400000");
        assertThat(first.items()).filteredOn(line -> line.orderChecklistItemId() != null)
                .extracting(line -> line.category(), line -> line.amount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PricingCategory.CHECKLIST, new BigDecimal("100000")),
                        org.assertj.core.groups.Tuple.tuple(PricingCategory.MEDIA, new BigDecimal("50000")),
                        org.assertj.core.groups.Tuple.tuple(PricingCategory.MEDIA, new BigDecimal("50000")),
                        org.assertj.core.groups.Tuple.tuple(PricingCategory.AREA, new BigDecimal("200000")));

        PricingRule area200 = rule(PricingCategory.AREA, "AREA", PricingCalculationType.PER_BLOCK, "200000");
        area200.setMinValue(BigDecimal.TEN); area200.setMaxValue(new BigDecimal("50")); area200.setUnitSize(BigDecimal.TEN);
        policy(2, area200, image, video);
        var second = engine.calculate(pricedOrder, List.of(item));

        assertThat(second.policyVersion()).isEqualTo(2);
        assertThat(second.items()).filteredOn(line -> line.category() == PricingCategory.AREA)
                .singleElement().satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("400000"));
        assertThat(second.systemCalculatedAmount()).isEqualByComparingTo("600000");
    }
}
