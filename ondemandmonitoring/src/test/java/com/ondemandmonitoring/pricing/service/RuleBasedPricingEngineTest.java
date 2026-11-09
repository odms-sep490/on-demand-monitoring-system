package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.pricing.domain.*;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuleBasedPricingEngineTest {
    PricingPolicyRepository policies = mock(PricingPolicyRepository.class);
    CustomWorkAssessmentRepository assessments = mock(CustomWorkAssessmentRepository.class);
    RuleBasedPricingEngine engine = new RuleBasedPricingEngine(policies, assessments, new ChecklistPriceCalculator());

    @Test void calculatesExplainableDistanceChecklistAndOutputComponents() {
        PricingPolicy policy = policy();
        policy.replaceRules(List.of(
                percent(PricingCategory.DISTANCE, "D20", "Distance 5-20 km", "5", "20", "10", 1),
                fixed(PricingCategory.CHECKLIST, "HARD", "Hard checklist", "150000", 2),
                fixed(PricingCategory.OUTPUT, "VIDEO", "Requested video", "300000", 3),
                multiplier("FLAT", "Flat terrain", "1.0", 4)));
        when(policies.findEffectiveActive(any(Instant.class))).thenReturn(List.of(policy));
        when(assessments.findByOrderId("o1")).thenReturn(Optional.empty());
        Order order = order("5000000"); order.setEstimatedLengthM(10_000d); order.setResultFormats(List.of(OrderResultFormat.VIDEO));
        OrderChecklistItem checklist = new OrderChecklistItem(); checklist.setDifficultySnapshot(ChecklistDifficulty.HARD);
        checklist.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE); checklist.setReviewStatus(ChecklistReviewStatus.INCLUDED);

        var result = engine.calculate(order, List.of(checklist));

        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("5950000");
        assertThat(result.items()).extracting(item -> item.category()).containsExactly(
                PricingCategory.PACKAGE, PricingCategory.DISTANCE, PricingCategory.CHECKLIST, PricingCategory.OUTPUT);
    }

    @Test void legacyCustomWorkNoLongerRequiresACompletedAssessment() {
        PricingPolicy policy = policy(); policy.replaceRules(List.of(fixed(PricingCategory.RESOURCE, "CUSTOM_BASE", "Custom base", "2000000", 1)));
        when(policies.findEffectiveActive(any())).thenReturn(List.of(policy));
        Order order = order("3000000");
        OrderChecklistItem custom = new OrderChecklistItem(); custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setReviewStatus(ChecklistReviewStatus.ADDITIONAL); custom.setDifficultySnapshot(ChecklistDifficulty.BASIC);
        when(assessments.findByOrderId("o1")).thenReturn(Optional.empty());
        var result = engine.calculate(order, List.of(custom));
        assertThat(result.customWork()).isTrue(); assertThat(result.assessmentComplete()).isTrue();
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("5000000");
    }

    @Test void managerRecalculationUsesThePolicyFrozenAtRequestSubmission() {
        PricingPolicy requestPolicy = policy();
        requestPolicy.replaceRules(List.of(fixed(PricingCategory.OUTPUT, "VIDEO", "Video v3", "300000", 1)));
        PricingPolicy newlyActive = policy(); newlyActive.setId("p2"); newlyActive.setPolicyVersion(4);
        newlyActive.replaceRules(List.of(fixed(PricingCategory.OUTPUT, "VIDEO", "Video v4", "500000", 1)));
        when(policies.findWithRulesById("p1")).thenReturn(Optional.of(requestPolicy));
        when(policies.findEffectiveActive(any())).thenReturn(List.of(newlyActive));
        Order order = order("4000000"); order.setPricingPolicyId("p1"); order.setPricingPolicyVersion(3);
        order.setResultFormats(List.of(OrderResultFormat.VIDEO));
        OrderChecklistItem legacyChecklist = new OrderChecklistItem();
        legacyChecklist.setPricingSnapshotVersion(0);

        var result = engine.calculate(order, List.of(legacyChecklist));

        assertThat(result.policyId()).isEqualTo("p1");
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("4300000");
        verify(policies, never()).findEffectiveActive(any());
    }

    private PricingPolicy policy() { PricingPolicy p = new PricingPolicy(); p.setId("p1"); p.setPolicyVersion(3); p.setStatus(PricingPolicyStatus.ACTIVE); p.setCurrency("VND"); return p; }
    private Order order(String price) { var service = new com.ondemandmonitoring.service.domain.Service(); service.setName("Inspection"); service.setBasePrice(new BigDecimal(price)); Order o = new Order(); o.setId("o1"); o.setService(service); return o; }
    private PricingRule fixed(PricingCategory c, String key, String text, String amount, int priority) { PricingRule r = base(c, key, text, PricingCalculationType.FIXED, priority); r.setFixedAmount(new BigDecimal(amount)); return r; }
    private PricingRule percent(PricingCategory c, String key, String text, String min, String max, String value, int priority) { PricingRule r = base(c, key, text, PricingCalculationType.PERCENT_OF_BASE, priority); r.setMinValue(new BigDecimal(min)); r.setMaxValue(new BigDecimal(max)); r.setPercentage(new BigDecimal(value)); return r; }
    private PricingRule multiplier(String key, String text, String value, int priority) { PricingRule r = base(PricingCategory.TERRAIN, key, text, PricingCalculationType.MULTIPLIER, priority); r.setMultiplier(new BigDecimal(value)); return r; }
    private PricingRule base(PricingCategory c, String key, String text, PricingCalculationType type, int priority) { PricingRule r = new PricingRule(); r.setCategory(c); r.setRuleKey(key); r.setDescription(text); r.setCalculationType(type); r.setPriority(priority); r.setEnabled(true); return r; }
}
