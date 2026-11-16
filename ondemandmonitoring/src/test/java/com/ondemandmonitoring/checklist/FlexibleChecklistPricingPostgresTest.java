package com.ondemandmonitoring.checklist;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.request.ServiceChecklistRequirementsRequest;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.request.OrderChecklistItemRequest;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.CustomWorkAssessmentRepository;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator;
import com.ondemandmonitoring.pricing.service.RuleBasedPricingEngine;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL (isolated random schema of the dedicated verification database). */
class FlexibleChecklistPricingPostgresTest extends ChecklistPostgresTest {
    @Autowired PricingPolicyRepository policyRepository;

    PricingRule media(String profile, MediaPricingComponent component, String price) {
        var r = new PricingRule();
        r.setCategory(PricingCategory.MEDIA); r.setPricingProfile(profile); r.setMediaComponent(component);
        r.setRuleKey(profile + ":" + component); r.setDescription(component.name());
        r.setCalculationType(PricingCalculationType.FIXED); r.setFixedAmount(new BigDecimal(price)); r.setEnabled(true);
        return r;
    }
    PricingRule tier(String from, String to, String price) {
        var r = media("STANDARD", MediaPricingComponent.EXTRA_IMAGE, price);
        r.setRuleKey("STANDARD:EXTRA_IMAGE:" + from); r.setCalculationType(PricingCalculationType.PER_BLOCK);
        r.setMinValue(new BigDecimal(from)); r.setMaxValue(to == null ? null : new BigDecimal(to));
        r.setUnitSize(BigDecimal.TEN); r.setRoundingMode(PricingRounding.CEILING);
        return r;
    }
    PricingPolicy publish(int version, String includedImagePrice, String firstTier, String secondTier) {
        return transaction.execute(status -> {
            for (var active : policyRepository.findAllByStatus(PricingPolicyStatus.ACTIVE)) {
                active.setStatus(PricingPolicyStatus.SUPERSEDED); active.setEffectiveTo(Instant.now().minusSeconds(5));
            }
            var p = new PricingPolicy();
            p.setName("Policy v" + version); p.setPolicyVersion(version); p.setStatus(PricingPolicyStatus.ACTIVE); p.setCurrency("VND");
            p.setEffectiveFrom(Instant.now().minusSeconds(60));
            p.setManagerMaxDiscountPercent(new BigDecimal("25")); p.setManagerMaxMarkupPercent(new BigDecimal("25"));
            p.setHighRiskDiscountPercent(new BigDecimal("10")); p.setMinimumPriceRatio(new BigDecimal("0.75")); p.setMinimumPrice(new BigDecimal("1000"));
            p.replaceRules(List.of(media("STANDARD", MediaPricingComponent.INCLUDED_IMAGE, includedImagePrice),
                    tier("0", "10", firstTier), tier("10", null, secondTier)));
            return policyRepository.saveAndFlush(p);
        });
    }
    String pricedChecklist(String content) {
        var r = new ChecklistRequest();
        r.setContent(content); r.setBasePrice(new BigDecimal("200000")); r.setIncludedImageCount(10); r.setIncludedVideoCount(0);
        r.setStandardAreaM2(new BigDecimal("100"));
        return catalog.create(r).getId();
    }
    void resetPricing() {
        new JdbcTemplate(dataSource).execute("TRUNCATE pricing_policies CASCADE");
    }

    @Test void checklistPricingConfigurationRoundTripsAndDatabaseConstraintsHold() {
        resetPricing();
        String id = pricedChecklist("Check roof");
        var stored = catalog.getById(id);
        assertEquals(0, new BigDecimal("200000").compareTo(stored.getBasePrice()));
        assertEquals(0, new BigDecimal("100").compareTo(stored.getStandardAreaM2()));
        assertEquals(10, stored.getIncludedImageCount());
        var edit = new ChecklistRequest(); edit.setContent("Check roof"); edit.setIncludedImageCount(25);
        assertEquals(25, catalog.update(id, edit).getIncludedImageCount());
        assertEquals(0, new BigDecimal("200000").compareTo(catalog.getById(id).getBasePrice()));

        var jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update checklist_definitions set base_price = -1 where id = ?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update checklist_definitions set included_image_count = 501 where id = ?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update checklist_definitions set included_video_count = -1 where id = ?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update checklist_definitions set standard_video_duration_seconds = 0 where id = ?", id));
    }

    @Test void associationOverridesArePerServiceAndConstrained() {
        String s1 = service("A"), s2 = service("B"), c = pricedChecklist("Check roof");
        assignments.assign(s1, assignment(c, 0));
        assignments.assign(s2, assignment(c, 0));
        var requirements = new ServiceChecklistRequirementsRequest();
        requirements.setIncludedImageCountOverride(20); requirements.setRequired(true);
        assignments.updateRequirements(s1, c, requirements);

        var first = assignments.getByService(s1, false).getFirst();
        var second = assignments.getByService(s2, false).getFirst();
        assertEquals(20, first.getIncludedImageCount());
        assertTrue(first.isRequired());
        assertEquals(10, second.getIncludedImageCount());
        assertFalse(second.isRequired());
        assertEquals(10, catalog.getById(c).getIncludedImageCount());

        var jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update service_checklists set included_video_count_override = 600 where service_id = ?", s1));
    }

    @Test void orderSnapshotFreezesBasePriceAndUsesCurrentChecklistStandardArea() {
        resetPricing();
        publish(1, "50000", "50000", "40000");
        String s = service("Priced"), c = pricedChecklist("Check roof");
        assignments.assign(s, assignment(c, 0));
        var item = new OrderChecklistItemRequest();
        item.setSourceChecklistId(c); item.setExpectedChecklistVersion(catalog.getById(c).getVersion());
        item.setRequestedImageCount(30); item.setRequestedVideoCount(0);
        var request = orderRequest(s);
        request.setChecklistItems(List.of(item));

        var created = orderService.createOrder(request);
        var response = created.getChecklistItems().getFirst();
        assertEquals(2, response.getPricingSnapshotVersion());
        assertEquals(30, response.getRequestedImageCount());
        assertEquals(10, response.getIncludedImageCountSnapshot());
        assertEquals(3, response.getEvidencePolicyVersion());
        assertEquals(30, response.getMinimumImageCount());
        assertEquals(RequiredEvidenceType.IMAGE, response.getRequiredEvidenceType());

        var engine = new RuleBasedPricingEngine(policyRepository, mock(CustomWorkAssessmentRepository.class), new ChecklistPriceCalculator());
        BigDecimal frozenTotal = transaction.execute(status -> {
            var order = orders.findById(created.getId()).orElseThrow();
            var snapshot = snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()).getFirst();
            return new com.ondemandmonitoring.pricing.service.BaseChecklistPriceCalculator()
                    .calculate(snapshot.getBasePriceSnapshot(), snapshot.getSourceChecklist().getStandardAreaM2(),
                            new com.ondemandmonitoring.pricing.service.BaseChecklistPriceCalculator().measureSquareMeters(order.getTargetArea()))
                    .calculatedPrice();
        });
        var firstPrice = transaction.execute(status -> {
            var order = orders.findById(created.getId()).orElseThrow();
            return engine.calculate(order, snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()));
        });
        assertEquals(0, frozenTotal.compareTo(firstPrice.systemCalculatedAmount()));
        assertEquals(0, firstPrice.policyVersion());

        // The catalog's current standard area is read at pricing time. The order's submitted base price and evidence remain frozen.
        var edit = new ChecklistRequest(); edit.setContent("Check roof"); edit.setBasePrice(new BigDecimal("999000"));
        edit.setStandardAreaM2(new BigDecimal("250")); edit.setIncludedImageCount(0);
        catalog.update(c, edit);
        assignments.unassign(s, c);
        publish(2, "90000", "70000", "60000");

        var frozen = transaction.execute(status -> snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()).stream()
                .map(i -> List.of(i.getBasePriceSnapshot().toPlainString(),
                        "" + i.getIncludedImageCountSnapshot(), "" + i.getRequestedImageCount(), "" + i.getPricingSnapshotVersion(),
                        "" + i.getMinimumImageCount())).toList());
        assertEquals(List.of(List.of("200000", "10", "30", "2", "30")), frozen);
        BigDecimal stillV1 = transaction.execute(status -> {
            var order = orders.findById(created.getId()).orElseThrow();
            return engine.calculate(order, snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()))
                    .systemCalculatedAmount();
        });
        BigDecimal currentStandardAreaTotal = transaction.execute(status -> {
            var order = orders.findById(created.getId()).orElseThrow();
            var snapshot = snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()).getFirst();
            var calculator = new com.ondemandmonitoring.pricing.service.BaseChecklistPriceCalculator();
            return calculator.calculate(snapshot.getBasePriceSnapshot(), snapshot.getSourceChecklist().getStandardAreaM2(),
                    calculator.measureSquareMeters(order.getTargetArea())).calculatedPrice();
        });
        assertEquals(0, currentStandardAreaTotal.compareTo(stillV1));
        assertNotEquals(0, frozenTotal.compareTo(stillV1), "the live standard area should be used when preparing a new quote");
    }

    @Test void legacySnapshotRowsKeepVersionZeroAndLegacyPricing() {
        String orderId = orderService.createOrder(orderRequest(service("Legacy"))).getId();
        resetPricing();
        // This fixture models a pre-policy-snapshot order, so it deliberately uses the legacy ACTIVE-policy fallback.
        new JdbcTemplate(dataSource).update(
                "update orders set pricing_policy_id = null, pricing_policy_version = null where id = ?", orderId);
        new JdbcTemplate(dataSource).update("""
                insert into order_checklist_items
                    (id,created_at,version,order_id,content,display_order,source_type,review_status,
                     evidence_policy_version,minimum_evidence_count,required_evidence_type,difficulty_snapshot)
                values ('legacy-item',now(),0,?,'Old checklist',0,'CUSTOMER_CUSTOM','PENDING',1,1,'IMAGE_OR_VIDEO','HARD')
                """, orderId);
        var item = transaction.execute(status -> snapshots.findById("legacy-item").orElseThrow());
        assertEquals(0, item.getPricingSnapshotVersion());
        assertEquals(0, new BigDecimal("0").compareTo(item.getBasePriceSnapshot()));
        assertEquals(0, item.getRequestedImageCount());
        assertEquals(0, item.getMinimumImageCount());

        var rule = new PricingRule();
        rule.setCategory(PricingCategory.CHECKLIST); rule.setRuleKey("HARD"); rule.setDescription("Hard"); rule.setCalculationType(PricingCalculationType.FIXED);
        rule.setFixedAmount(new BigDecimal("150000")); rule.setEnabled(true);
        var legacyCustom = new PricingRule();
        legacyCustom.setCategory(PricingCategory.RESOURCE); legacyCustom.setRuleKey("CUSTOM_BASE"); legacyCustom.setDescription("Custom");
        legacyCustom.setCalculationType(PricingCalculationType.FIXED); legacyCustom.setFixedAmount(new BigDecimal("2000000")); legacyCustom.setEnabled(true);
        transaction.executeWithoutResult(status -> {
            var p = new PricingPolicy();
            p.setName("Legacy policy"); p.setPolicyVersion(1); p.setStatus(PricingPolicyStatus.ACTIVE); p.setCurrency("VND"); p.setEffectiveFrom(Instant.now().minusSeconds(60));
            p.setManagerMaxDiscountPercent(BigDecimal.TEN); p.setManagerMaxMarkupPercent(BigDecimal.TEN); p.setHighRiskDiscountPercent(BigDecimal.ONE);
            p.setMinimumPriceRatio(new BigDecimal("0.5")); p.setMinimumPrice(BigDecimal.TEN);
            p.replaceRules(List.of(rule, legacyCustom)); policyRepository.saveAndFlush(p);
        });
        var engine = new RuleBasedPricingEngine(policyRepository, mock(CustomWorkAssessmentRepository.class), new ChecklistPriceCalculator());
        var total = transaction.execute(status -> {
            var order = orders.findById(orderId).orElseThrow();
            var loaded = snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId);
            loaded.forEach(i -> i.setReviewStatus(ChecklistReviewStatus.ADDITIONAL));
            return engine.calculate(order, loaded).systemCalculatedAmount();
        });
        // package 3,200,000 + difficulty HARD 150,000 + the single order-level custom charge 2,000,000 (not per item)
        assertEquals(0, new BigDecimal("5350000").compareTo(total));
    }

    @Test void evidencePolicyV3ConstraintAcceptsConsistentRowsAndRejectsInconsistentOnes() {
        String orderId = orderService.createOrder(orderRequest(service("V3"))).getId();
        var jdbc = new JdbcTemplate(dataSource);
        String sql = "insert into order_checklist_items(id,created_at,version,order_id,content,display_order,source_type,review_status,"
                + "evidence_policy_version,minimum_evidence_count,required_evidence_type,minimum_image_count,minimum_video_count) "
                + "values (?,now(),0,?,?,?,'CUSTOMER_CUSTOM','PENDING',?,?,?,?,?)";
        assertEquals(1, jdbc.update(sql, "ok-both", orderId, "both", 0, 3, 5, "IMAGE_OR_VIDEO", 4, 1));
        assertEquals(1, jdbc.update(sql, "ok-images", orderId, "images", 1, 3, 2, "IMAGE", 2, 0));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad-sum", orderId, "x1", 2, 3, 5, "IMAGE_OR_VIDEO", 2, 1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad-zero", orderId, "x2", 3, 3, 0, "IMAGE", 0, 0));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad-neg", orderId, "x3", 4, 3, 1, "IMAGE", 3, -2));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "v2-with-split", orderId, "x4", 5, 2, 3, "IMAGE", 2, 1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad-version", orderId, "x5", 6, 4, 1, "IMAGE", 1, 0));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad-type", orderId, "x6", 7, 3, 1, "STREAMING", 1, 0));
        verifyCurrentSchema();
    }
    // ---- Stored Base Checklist default price + legacy Manager-quoted custom checklists -------------------------------
    private BigDecimal storedDefault(String serviceId) {
        return transaction.execute(status -> services.findById(serviceId).orElseThrow().getDefaultPrice());
    }
    private BigDecimal liveDefault(String serviceId) {
        var resolver = new com.ondemandmonitoring.pricing.service.ServicePriceResolver(links);
        return transaction.execute(status -> resolver.resolve(services.findById(serviceId).orElseThrow()).checklistSubtotal());
    }
    private void assertStoredMatchesLive(String serviceId, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(storedDefault(serviceId)), "stored default price");
        assertEquals(0, storedDefault(serviceId).compareTo(liveDefault(serviceId)), "stored == recomputed from the source of truth");
    }

    @Test void storedServiceDefaultPriceFollowsGlobalBaseChecklistPricesAndAssociations() {
        resetPricing();
        String s = service("Stored price"), c1 = pricedChecklist("Check roof"), c2 = pricedChecklist("Check wall");
        assertNull(storedDefault(s));

        assignments.assign(s, assignment(c1, 0));
        assertStoredMatchesLive(s, "200000");
        var wall = new ChecklistRequest(); wall.setContent("Check wall"); wall.setBasePrice(new BigDecimal("100000")); wall.setIncludedImageCount(0);
        catalog.update(c2, wall);
        assignments.assign(s, assignment(c2, 1));
        assertStoredMatchesLive(s, "300000");

        var roof = new ChecklistRequest(); roof.setContent("Check roof"); roof.setBasePrice(new BigDecimal("300000"));
        catalog.update(c1, roof);                                  // editing the checklist refreshes every service that uses it
        assertStoredMatchesLive(s, "400000");

        var requirements = new ServiceChecklistRequirementsRequest(); requirements.setIncludedImageCountOverride(12);
        assignments.updateRequirements(s, c1, requirements);        // evidence requirements do not affect the price
        assertStoredMatchesLive(s, "400000");

        assignments.unassign(s, c2);                               // removing a checklist removes its price
        assertStoredMatchesLive(s, "300000");

        catalog.updateStatus(c1, false);
        assertStoredMatchesLive(s, "0");
    }

    @Test void customChecklistNeedsConfiguredPolicyAndManagerReviewWithoutLegacyPackage() {
        resetPricing();
        publish(1, "50000", "50000", "40000");                     // no CUSTOM profile rule
        String s = service("Custom quote");
        var custom = new OrderChecklistItemRequest();
        custom.setContentOverride("Check the antenna mast"); custom.setRequestedImageCount(4);
        var request = orderRequest(s);
        request.setChecklistItems(List.of(custom));

        var unconfigured = assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> orderService.createOrder(request));
        assertEquals(ErrorCode.CUSTOM_CHECKLIST_PRICING_NOT_CONFIGURED, unconfigured.getErrorCode());
        transaction.executeWithoutResult(status -> new com.ondemandmonitoring.pricing.config.PricingPolicyInitializer(policyRepository).run(null));
        var created = orderService.createOrder(request);
        var response = created.getChecklistItems().getFirst();
        assertEquals(com.ondemandmonitoring.order.enums.ChecklistPricingStatus.NEEDS_MANAGER_REVIEW,
                response.getPricingStatus());
        assertFalse(response.isPriceAvailable());
        assertNull(response.getApprovedPrice());

        var engine = new RuleBasedPricingEngine(policyRepository, mock(CustomWorkAssessmentRepository.class), new ChecklistPriceCalculator());
        var pending = transaction.execute(status -> engine.calculate(orders.findById(created.getId()).orElseThrow(),
                snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId())));
        // The engine calculates the policy price; explicit Manager review is enforced by QuoteService.
        assertTrue(snapshots.findById(response.getId()).orElseThrow().isManagerQuoteRequired());
        assertTrue(pending.pendingQuoteItems().isEmpty());
        assertEquals(0, new BigDecimal("200000").compareTo(pending.systemCalculatedAmount()));
        assertTrue(pending.items().stream().noneMatch(line -> line.category() == PricingCategory.PACKAGE));

        transaction.executeWithoutResult(status -> snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId())
                .forEach(i -> i.setManagerQuotedPrice(new BigDecimal("450000"))));
        var quoted = transaction.execute(status -> engine.calculate(orders.findById(created.getId()).orElseThrow(),
                snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId())));
        assertTrue(quoted.pendingQuoteItems().isEmpty());
        // A manual quote does not rewrite the policy's system amount; QuoteService applies the audited adjustment.
        assertEquals(0, pending.systemCalculatedAmount().compareTo(quoted.systemCalculatedAmount()));
        assertEquals(0, new BigDecimal("450000").compareTo(
                snapshots.findById(response.getId()).orElseThrow().getManagerQuotedPrice()));

        // the database only lets a manager price sit on a checklist that required one
        var jdbc = new JdbcTemplate(dataSource);
        String orderId = created.getId();
        String insert = "insert into order_checklist_items(id,created_at,version,order_id,content,display_order,source_type,review_status,"
                + "evidence_policy_version,minimum_evidence_count,required_evidence_type,manager_quote_required,manager_quoted_price) "
                + "values (?,now(),0,?,?,?,'CUSTOMER_CUSTOM','PENDING',2,1,'IMAGE',?,1000)";
        assertEquals(1, jdbc.update(insert, "mq-ok", orderId, "MQ", 5, true));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, "mq-bad", orderId, "MQ2", 6, false));
    }
}
