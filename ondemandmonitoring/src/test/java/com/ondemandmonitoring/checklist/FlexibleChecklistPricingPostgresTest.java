package com.ondemandmonitoring.checklist;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.request.ServiceChecklistPricingRequest;
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
    @Autowired com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices;

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
        r.setStandardVideoDurationSeconds(30); r.setPricingProfile("standard");
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
        assertEquals(10, stored.getIncludedImageCount());
        assertEquals(30, stored.getStandardVideoDurationSeconds());
        assertEquals("STANDARD", stored.getPricingProfile());
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
        var pricing = new ServiceChecklistPricingRequest();
        pricing.setBasePriceOverride(new BigDecimal("260000")); pricing.setIncludedImageCountOverride(20); pricing.setRequired(true);
        assignments.updatePricing(s1, c, pricing);

        var first = assignments.getByService(s1, false).getFirst();
        var second = assignments.getByService(s2, false).getFirst();
        assertEquals(0, new BigDecimal("260000").compareTo(first.getBasePrice()));
        assertEquals(20, first.getIncludedImageCount());
        assertTrue(first.isRequired());
        assertEquals(0, new BigDecimal("200000").compareTo(second.getBasePrice()));
        assertEquals(10, second.getIncludedImageCount());
        assertFalse(second.isRequired());
        assertEquals(10, catalog.getById(c).getIncludedImageCount());

        var jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update service_checklists set base_price_override = -5 where service_id = ?", s1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update service_checklists set included_video_count_override = 600 where service_id = ?", s1));
    }

    @Test void mediaPolicyRulesPersistAndDatabaseRejectsMalformedMediaRules() {
        resetPricing();
        var policy = publish(1, "50000", "50000", "40000");
        var reloaded = transaction.execute(status -> policyRepository.findById(policy.getId()).orElseThrow().getRules().stream()
                .map(r -> r.getMediaComponent() + ":" + r.getPricingProfile() + ":" + r.getRoundingMode()).sorted().toList());
        assertEquals(List.of("EXTRA_IMAGE:STANDARD:CEILING", "EXTRA_IMAGE:STANDARD:CEILING", "INCLUDED_IMAGE:STANDARD:null"), reloaded);

        var jdbc = new JdbcTemplate(dataSource);
        String sql = "insert into pricing_rules(id,created_at,version,policy_id,category,calculation_type,rule_key,description,priority,enabled,"
                + "pricing_profile,media_component,fixed_amount,unit_size,min_value,max_value) values (?,now(),0,?,?,?,?,?,0,true,?,?,?,?,?,?)";
        // MEDIA rule without a profile
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad1", policy.getId(), "MEDIA", "FIXED", "K1", "d", null, "INCLUDED_IMAGE", 1, null, null, null));
        // negative price
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad2", policy.getId(), "MEDIA", "FIXED", "K2", "d", "STANDARD", "INCLUDED_IMAGE", -1, null, null, null));
        // extra tier with a zero block size
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad3", policy.getId(), "MEDIA", "PER_BLOCK", "K3", "d", "STANDARD", "EXTRA_VIDEO", 10, 0, 0, null));
        // range with max <= min
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad4", policy.getId(), "MEDIA", "PER_BLOCK", "K4", "d", "STANDARD", "EXTRA_VIDEO", 10, 1, 5, 5));
        // a non-MEDIA rule cannot carry a profile
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, "bad5", policy.getId(), "OUTPUT", "FIXED", "K5", "d", "STANDARD", null, 10, null, null, null));
    }

    @Test void orderSnapshotFreezesPricingInputsAndSurvivesCatalogAndPolicyChanges() {
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
        assertEquals(1, response.getPricingSnapshotVersion());
        assertEquals(30, response.getRequestedImageCount());
        assertEquals(10, response.getIncludedImageCountSnapshot());
        assertEquals(3, response.getEvidencePolicyVersion());
        assertEquals(30, response.getMinimumImageCount());
        assertEquals(RequiredEvidenceType.IMAGE, response.getRequiredEvidenceType());

        var engine = new RuleBasedPricingEngine(policyRepository, mock(CustomWorkAssessmentRepository.class), new ChecklistPriceCalculator());
        BigDecimal v1Total = transaction.execute(status -> {
            var order = orders.findById(created.getId()).orElseThrow();
            return engine.calculate(order, snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId())).systemCalculatedAmount();
        });
        // the service is priced by its checklist, so the legacy catalog price (3,200,000) is not added:
        // base 200,000 + included 50,000 + extra 50,000 + 40,000 (a new policy has no CHECKLIST difficulty rule)
        assertEquals(0, new BigDecimal("340000").compareTo(v1Total));

        // The catalog, the association and the policy all change afterwards.
        var edit = new ChecklistRequest(); edit.setContent("Check roof"); edit.setBasePrice(new BigDecimal("999000")); edit.setIncludedImageCount(0);
        catalog.update(c, edit);
        assignments.unassign(s, c);
        publish(2, "90000", "70000", "60000");

        var frozen = transaction.execute(status -> snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()).stream()
                .map(i -> List.of(i.getBasePriceSnapshot().toPlainString(), "" + i.getIncludedImageCountSnapshot(), "" + i.getRequestedImageCount(),
                        i.getPricingProfileSnapshot(), "" + i.getPricingSnapshotVersion(), "" + i.getMinimumImageCount())).toList());
        assertEquals(List.of(List.of("200000", "10", "30", "STANDARD", "1", "30")), frozen);
        BigDecimal stillV1 = transaction.execute(status -> {
            var order = orders.findById(created.getId()).orElseThrow();
            return engine.calculate(order, snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(created.getId()))
                    .systemCalculatedAmount();
        });
        assertEquals(0, new BigDecimal("340000").compareTo(stillV1),
                "a submitted order remains on its request-time pricing policy");
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
    // ---- Admin API flow for MEDIA rules: create draft -> edit -> publish -> price (real PostgreSQL) -------------------
    private com.ondemandmonitoring.pricing.service.PricingPolicyService policyService() {
        var policyService = new com.ondemandmonitoring.pricing.service.PricingPolicyService(policyRepository);
        policyService.setDefaultPrices(defaultPrices);
        return policyService;
    }
    private com.ondemandmonitoring.pricing.dto.PricingPolicyResponse createDraft(com.ondemandmonitoring.pricing.dto.PricingPolicyRequest request) {
        return transaction.execute(status -> policyService().createDraft(request));
    }
    private com.ondemandmonitoring.pricing.dto.PricingPolicyResponse updateDraft(String id, com.ondemandmonitoring.pricing.dto.PricingPolicyRequest request) {
        return transaction.execute(status -> policyService().updateDraft(id, request));
    }
    private com.ondemandmonitoring.pricing.dto.PricingRuleRequest mediaRule(String key, MediaPricingComponent component, String from,
            String to, String block, String price) {
        boolean tier = component == MediaPricingComponent.EXTRA_IMAGE || component == MediaPricingComponent.EXTRA_VIDEO;
        return new com.ondemandmonitoring.pricing.dto.PricingRuleRequest(PricingCategory.MEDIA,
                tier ? PricingCalculationType.PER_BLOCK : PricingCalculationType.FIXED, key, key,
                from == null ? null : new BigDecimal(from), to == null ? null : new BigDecimal(to), new BigDecimal(price), null, null,
                block == null ? null : new BigDecimal(block), null, 0, true, "standard", component, null, null);
    }
    private com.ondemandmonitoring.pricing.dto.PricingPolicyRequest policyRequest(com.ondemandmonitoring.pricing.dto.PricingRuleRequest... rules) {
        return new com.ondemandmonitoring.pricing.dto.PricingPolicyRequest("Media policy", null, null, new BigDecimal("25"), new BigDecimal("25"),
                new BigDecimal("10"), new BigDecimal("0.75"), new BigDecimal("1000"), List.of(rules));
    }
    private void publishViaAdminApi(com.ondemandmonitoring.pricing.dto.PricingPolicyRequest request) {
        var draft = createDraft(request);
        transaction.executeWithoutResult(status -> policyService().publish(draft.id()));
    }
    private com.ondemandmonitoring.pricing.service.ChecklistPricingPreviewService previewService() {
        var calculator = new ChecklistPriceCalculator();
        return new com.ondemandmonitoring.pricing.service.ChecklistPricingPreviewService(services, definitions, links, policyRepository, calculator,
                new com.ondemandmonitoring.pricing.service.ServicePriceResolver(links, policyRepository, calculator));
    }
    private com.ondemandmonitoring.pricing.dto.ServicePricePreviewResponse quote(String serviceId, String checklistId, Integer images, Integer videos) {
        return transaction.execute(status -> previewService().customerQuote(new com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.Customer(
                serviceId, List.of(new com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.CustomerItem(checklistId, null, images, videos)))));
    }

    @Test void adminCreatesEditsAndPublishesMediaTiersThatThePricingFollows() {
        resetPricing();
        String s = service("Media flow"), c = pricedChecklist("Check roof");
        assignments.assign(s, assignment(c, 0));
        var draft = createDraft(policyRequest(
                mediaRule("INC", MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "50000"),
                mediaRule("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000"),
                mediaRule("T2", MediaPricingComponent.EXTRA_IMAGE, "10", null, "10", "30000")));
        // edit the draft: the second tier becomes 40,000
        updateDraft(draft.id(), policyRequest(
                mediaRule("INC", MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "50000"),
                mediaRule("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000"),
                mediaRule("T2", MediaPricingComponent.EXTRA_IMAGE, "10", null, "10", "40000")));
        // a draft is not effective yet: pricing has no policy
        var none = assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> quote(s, c, 30, null));
        assertEquals(ErrorCode.PRICING_POLICY_NOT_FOUND, none.getErrorCode());
        assertEquals(409, none.getStatus().value());

        transaction.executeWithoutResult(status -> policyService().publish(draft.id()));

        var result = quote(s, c, 30, null);
        // 200,000 base + 50,000 included images + 50,000 (first extra block) + 40,000 (next extra block); no legacy price added
        assertEquals(0, new BigDecimal("340000").compareTo(result.total()));
        assertEquals("CHECKLISTS", result.priceSource());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.packageFee()));
        assertEquals(List.of("BASE", "INCLUDED_IMAGE", "EXTRA_IMAGE", "EXTRA_IMAGE"),
                result.checklists().getFirst().lines().stream().map(l -> l.component()).toList());
        // 25 extra images: tier 1 = 1 block (50,000), tier 2 = 15 images = ceil(1.5) = 2 blocks (80,000); 200,000 + 50,000 + 130,000
        assertEquals(0, new BigDecimal("380000").compareTo(quote(s, c, 35, null).total()));
        // published policies are immutable
        assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> updateDraft(draft.id(), policyRequest(
                mediaRule("INC", MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "1"))));
    }

    @Test void conflictOnlyForMissingTierMissingCoverageOrMissingPolicyAndItsMessageSaysHowToFix() {
        resetPricing();
        String s = service("Conflicts"), c = pricedChecklist("Check roof");
        assignments.assign(s, assignment(c, 0));
        publishViaAdminApi(policyRequest(
                mediaRule("INC", MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "50000"),
                mediaRule("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000")));

        // covered quantities never conflict
        assertEquals(0, new BigDecimal("250000").compareTo(quote(s, c, 10, null).total()));
        assertEquals(0, new BigDecimal("300000").compareTo(quote(s, c, 20, null).total()));
        // beyond the last bounded tier
        var uncovered = assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> quote(s, c, 21, null));
        assertEquals(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED, uncovered.getErrorCode());
        assertEquals(409, uncovered.getStatus().value());
        assertTrue(uncovered.getMessage().contains("extend the tiers") && uncovered.getMessage().contains("publish"), uncovered.getMessage());
        // no video tier configured at all
        var noTier = assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> quote(s, c, null, 1));
        assertEquals(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED, noTier.getErrorCode());
        assertTrue(noTier.getMessage().contains("add a tier starting at 0"), noTier.getMessage());
        // the same selection is accepted at order capture time only when it is priceable
        new JdbcTemplate(dataSource).execute("TRUNCATE pricing_policies CASCADE");
        assertEquals(ErrorCode.PRICING_POLICY_NOT_FOUND,
                assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> quote(s, c, 10, null)).getErrorCode());
    }

    @Test void invalidMediaPolicyIsRejectedBeforeItIsStored() {
        resetPricing();
        var overlap = assertThrows(com.ondemandmonitoring.common.exception.ApiException.class, () -> createDraft(policyRequest(
                mediaRule("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000"),
                mediaRule("T2", MediaPricingComponent.EXTRA_IMAGE, "5", null, "10", "40000"))));
        assertEquals(400, overlap.getStatus().value());
        assertEquals(0, policyRepository.count());
    }

    // ---- Stored service default price + Manager-quoted custom checklists -----------------------------------------------
    private BigDecimal storedDefault(String serviceId) {
        return transaction.execute(status -> services.findById(serviceId).orElseThrow().getDefaultPrice());
    }
    private BigDecimal liveDefault(String serviceId) {
        var calculator = new ChecklistPriceCalculator();
        var resolver = new com.ondemandmonitoring.pricing.service.ServicePriceResolver(links, policyRepository, calculator);
        return transaction.execute(status -> resolver.resolve(services.findById(serviceId).orElseThrow(),
                policyRepository.findEffectiveActive(Instant.now()).getFirst()).checklistSubtotal());
    }
    private void assertStoredMatchesLive(String serviceId, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(storedDefault(serviceId)), "stored default price");
        assertEquals(0, storedDefault(serviceId).compareTo(liveDefault(serviceId)), "stored == recomputed from the source of truth");
    }

    @Test void storedServiceDefaultPriceFollowsChecklistsAssociationsAndPolicy() {
        resetPricing();
        publishViaAdminApi(policyRequest(mediaRule("INC", MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "50000")));
        String s = service("Stored price"), c1 = pricedChecklist("Check roof"), c2 = pricedChecklist("Check wall");
        assertNull(storedDefault(s));

        assignments.assign(s, assignment(c1, 0));
        assertStoredMatchesLive(s, "250000");                      // 200,000 + 50,000 included images
        var wall = new ChecklistRequest(); wall.setContent("Check wall"); wall.setBasePrice(new BigDecimal("100000")); wall.setIncludedImageCount(0);
        catalog.update(c2, wall);
        assignments.assign(s, assignment(c2, 1));
        assertStoredMatchesLive(s, "350000");                      // adding a default checklist adds its price

        var roof = new ChecklistRequest(); roof.setContent("Check roof"); roof.setBasePrice(new BigDecimal("300000"));
        catalog.update(c1, roof);                                  // editing the checklist refreshes every service that uses it
        assertStoredMatchesLive(s, "450000");

        var override = new ServiceChecklistPricingRequest(); override.setBasePriceOverride(new BigDecimal("100000"));
        assignments.updatePricing(s, c1, override);                // per-service override only
        assertStoredMatchesLive(s, "250000");

        assignments.unassign(s, c2);                               // removing a checklist removes its price
        assertStoredMatchesLive(s, "150000");

        publishViaAdminApi(policyRequest(mediaRule("INC", MediaPricingComponent.INCLUDED_IMAGE, null, null, null, "80000")));
        assertStoredMatchesLive(s, "180000");                      // a new policy version refreshes every service

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
