package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.CustomWorkAssessmentRepository;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Work done on every visit is billed once per visit; order-level charges are billed once. */
class RecurringOrderPricingTest {
    PricingPolicyRepository policies = mock(PricingPolicyRepository.class);
    CustomWorkAssessmentRepository assessments = mock(CustomWorkAssessmentRepository.class);
    RuleBasedPricingEngine engine = new RuleBasedPricingEngine(policies, assessments, new ChecklistPriceCalculator());

    PricingRule fixed(PricingCategory category, String key, String price) {
        PricingRule r = new PricingRule();
        r.setCategory(category); r.setRuleKey(key); r.setDescription(key);
        r.setCalculationType(PricingCalculationType.FIXED); r.setFixedAmount(new BigDecimal(price)); r.setEnabled(true);
        return r;
    }

    PricingRule includedImage(String price) {
        PricingRule r = fixed(PricingCategory.MEDIA, "STANDARD:INCLUDED_IMAGE", price);
        r.setPricingProfile("STANDARD"); r.setMediaComponent(MediaPricingComponent.INCLUDED_IMAGE);
        return r;
    }

    void policy(PricingRule... rules) {
        PricingPolicy p = new PricingPolicy();
        p.setId("p1"); p.setPolicyVersion(1); p.setStatus(PricingPolicyStatus.ACTIVE); p.setCurrency("VND");
        p.replaceRules(List.of(rules));
        when(policies.findEffectiveActive(any(Instant.class))).thenReturn(List.of(p));
    }

    /** A current-style order: no stored package price, so the checklists are the whole price. */
    Order order(OrderRecurrenceType type, Integer occurrences) {
        var service = new com.ondemandmonitoring.service.domain.Service();
        service.setName("Inspection");
        Order o = new Order();
        o.setId("o1"); o.setService(service);
        o.setRecurrenceType(type); o.setRecurrenceOccurrences(occurrences);
        return o;
    }

    OrderChecklistItem item() {
        OrderChecklistItem item = new OrderChecklistItem();
        item.setId("i1");
        item.setContent("Check roof"); item.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE);
        item.setReviewStatus(ChecklistReviewStatus.INCLUDED); item.setDifficultySnapshot(ChecklistDifficulty.BASIC);
        item.setPricingSnapshotVersion(1); item.setBasePriceSnapshot(new BigDecimal("200000"));
        item.setIncludedImageCountSnapshot(10); item.setIncludedVideoCountSnapshot(0);
        item.setRequestedImageCount(10); item.setRequestedVideoCount(0);
        item.setPricingProfileSnapshot("STANDARD");
        return item;
    }

    @Test void aSingleVisitIsPricedOnce() {
        policy(includedImage("50000"));
        var once = engine.calculate(order(OrderRecurrenceType.NONE, null), List.of(item()));
        assertThat(once.systemCalculatedAmount()).isEqualByComparingTo("250000");
    }

    @Test void aRepeatingOrderBillsEveryVisit() {
        policy(includedImage("50000"));
        var result = engine.calculate(order(OrderRecurrenceType.MONTHLY, 3), List.of(item()));
        // (200,000 base + 50,000 included images) x 3 visits
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("750000");
        assertThat(result.items()).allSatisfy(line -> {
            assertThat(line.description()).contains("× 3 lần");
            // quantity x unit price still equals the amount, so the line stays explainable
            assertThat(line.unitPrice().multiply(line.quantity())).isEqualByComparingTo(line.amount());
            assertThat(line.orderChecklistItemId()).isEqualTo("i1");
        });
    }

    @Test void theManagerSeesTheWholeChecklistPriceAcrossVisits() {
        policy(includedImage("50000"));
        var result = engine.calculate(order(OrderRecurrenceType.WEEKLY, 4), List.of(item()));
        var perChecklist = result.items().stream().map(line -> line.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(perChecklist).isEqualByComparingTo("1000000");
    }

    @Test void orderLevelChargesAreNotMultiplied() {
        policy(includedImage("50000"), fixed(PricingCategory.PERMIT, "REQUIRED", "100000"));
        Order repeating = order(OrderRecurrenceType.MONTHLY, 3);
        repeating.getFlightCompliance().setPermitRequired(true);
        var result = engine.calculate(repeating, List.of(item()));
        // 750,000 for the three visits + one permit
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("850000");
    }

    @Test void videoDeliverableIsChargedOnceEvenWithManyChecklistsAndVisits() {
        policy(includedImage("50000"), fixed(PricingCategory.OUTPUT, "VIDEO", "300000"));
        Order repeating = order(OrderRecurrenceType.MONTHLY, 3);
        repeating.setResultFormats(List.of(com.ondemandmonitoring.order.enums.OrderResultFormat.VIDEO));
        OrderChecklistItem second = item(); second.setId("i2"); second.setContent("Check wall");
        var result = engine.calculate(repeating, List.of(item(), second));
        assertThat(result.items()).filteredOn(line -> line.category() == PricingCategory.OUTPUT)
                .singleElement().satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("300000"));
        // two 250k checklist prices x three visits, plus one order-level deliverable
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("1800000");
    }

    @Test void aMissingOrAbsurdCountIsKeptInsideSafeLimits() {
        assertThat(PricingOccurrences.of(order(OrderRecurrenceType.MONTHLY, null))).isEqualTo(1);
        assertThat(PricingOccurrences.of(order(OrderRecurrenceType.MONTHLY, 0))).isEqualTo(1);
        assertThat(PricingOccurrences.of(order(OrderRecurrenceType.MONTHLY, 5000))).isEqualTo(PricingOccurrences.MAX);
        assertThat(PricingOccurrences.of(order(OrderRecurrenceType.NONE, 9))).isEqualTo(1);
    }

    @Test void legacyOrdersUseTheCatalogBaseAndKeepTheirSingleVisitPrice() {
        policy(includedImage("50000"), fixed(PricingCategory.CHECKLIST, "BASIC", "250000"));
        Order legacy = order(OrderRecurrenceType.MONTHLY, 3);
        legacy.getService().setBasePrice(new BigDecimal("1000000"));
        OrderChecklistItem legacyItem = item();
        legacyItem.setPricingSnapshotVersion(0);
        var result = engine.calculate(legacy, List.of(legacyItem));
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("1250000");
    }

    @Test void aBreakdownScalesQuantityAndAmountButNotTheUnitPrice() {
        var line = new ChecklistPriceBreakdown.Line("BASE", PricingCategory.CHECKLIST, "Base", BigDecimal.ONE,
                new BigDecimal("200000"), new BigDecimal("200000"), null, null, null, null);
        var one = new ChecklistPriceBreakdown("c1", "Roof", "STANDARD", false, 10, 0, 10, 0, 0, 0, null,
                List.of(line), new BigDecimal("200000"));
        var three = one.times(3);
        assertThat(three.subtotal()).isEqualByComparingTo("600000");
        assertThat(three.lines().getFirst().quantity()).isEqualByComparingTo("3");
        assertThat(three.lines().getFirst().unitPrice()).isEqualByComparingTo("200000");
        assertThat(three.includedImageCount()).isEqualTo(10);
        assertThat(one.times(1)).isSameAs(one);
    }
}
