package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.dto.PricingPolicyRequest;
import com.ondemandmonitoring.pricing.dto.PricingRuleRequest;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PricingPolicyMediaValidationTest {
    PricingPolicyRepository repository = mock(PricingPolicyRepository.class);
    ChecklistDefinitionRepository checklistDefinitions = mock(ChecklistDefinitionRepository.class);
    PricingPolicyService service = new PricingPolicyService(repository);
    { service.setChecklistDefinitions(checklistDefinitions); }

    PricingRuleRequest tier(String key, MediaPricingComponent component, String from, String to, String block, String price) {
        return new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.PER_BLOCK, key, "tier " + key,
                from == null ? null : new BigDecimal(from), to == null ? null : new BigDecimal(to), new BigDecimal(price), null, null,
                block == null ? null : new BigDecimal(block), null, 0, true, "standard", component, null, null);
    }
    PricingRuleRequest included(String key, MediaPricingComponent component, String price, Integer allowance, String profile) {
        return new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.FIXED, key, "included " + key,
                null, null, new BigDecimal(price), null, null, null, null, 0, true, profile, component, allowance, null);
    }
    PricingPolicyRequest request(PricingRuleRequest... rules) {
        return new PricingPolicyRequest("Policy", null, null, new BigDecimal("25"), new BigDecimal("25"), new BigDecimal("10"),
                new BigDecimal("0.75"), new BigDecimal("100000"), List.of(rules));
    }
    PricingPolicyRequest request(List<PricingRuleRequest> rules) { return request(rules.toArray(PricingRuleRequest[]::new)); }

    @Test void validTierScheduleIsStoredWithNormalisedProfileAndDefaults() {
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of());
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.createDraft(request(
                tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000"),
                tier("T2", MediaPricingComponent.EXTRA_IMAGE, "10", null, "10", "40000"),
                included("INC", MediaPricingComponent.INCLUDED_IMAGE, "50000", null, "standard")));

        assertThat(response.rules()).hasSize(3);
        assertThat(response.rules()).allSatisfy(r -> assertThat(r.pricingProfile()).isEqualTo("STANDARD"));
        assertThat(response.rules().get(0).roundingMode()).isEqualTo(PricingRounding.CEILING);
    }

    @Test void areaAllowanceAndRateBelongToTheCustomerAddedProfileOnly() {
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of());
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));
        var response = service.createDraft(request(
                included("AREA-INC", MediaPricingComponent.INCLUDED_AREA, "0", 5, "generic_customer_added"),
                included("AREA-EXTRA", MediaPricingComponent.EXTRA_AREA, "50000", null, "generic_customer_added")));
        assertThat(response.rules()).extracting(r -> r.mediaComponent())
                .containsExactlyInAnyOrder(MediaPricingComponent.INCLUDED_AREA, MediaPricingComponent.EXTRA_AREA);
        assertInvalid(request(included("AREA-EXTRA", MediaPricingComponent.EXTRA_AREA, "50000", null, "standard")),
                "customer-added profile only");
        assertInvalid(request(included("AREA-INC", MediaPricingComponent.INCLUDED_AREA, "0", 5, "default")),
                "customer-added profile only");
    }

    @Test void overlappingTiersAreRejected() {
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000"),
                tier("T2", MediaPricingComponent.EXTRA_IMAGE, "5", null, "10", "40000")), "overlap");
    }

    @Test void tierGapsAndTiersNotStartingAtZeroAreRejected() {
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", "10", "10", "50000"),
                tier("T2", MediaPricingComponent.EXTRA_IMAGE, "20", null, "10", "40000")), "gap");
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "5", null, "10", "50000")), "gap");
    }

    @Test void onlyTheLastTierMayBeOpenEnded() {
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "50000"),
                tier("T2", MediaPricingComponent.EXTRA_IMAGE, "10", null, "10", "40000")), "open-ended");
    }

    @Test void invalidRangeNegativePriceAndBadBlockSizeAreRejected() {
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "10", "10", "10", "50000")), "greater than minValue");
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "-1")), "negative");
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", null, "0", "1000")), "block size");
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", null, "1.5", "1000")), "block size");
        assertInvalid(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "1000.5")), "whole VND");
    }

    @Test void tiersOfDifferentProfilesOrComponentsMayShareRanges() {
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of());
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));
        var other = new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.PER_BLOCK, "P2", "premium", BigDecimal.ZERO, null,
                new BigDecimal("90000"), null, null, BigDecimal.TEN, null, 0, true, "PREMIUM", MediaPricingComponent.EXTRA_IMAGE, null, null);
        var video = tier("V1", MediaPricingComponent.EXTRA_VIDEO, "0", null, "1", "100000");
        assertThat(service.createDraft(request(tier("T1", MediaPricingComponent.EXTRA_IMAGE, "0", null, "10", "50000"), other, video)).rules()).hasSize(3);
    }

    @Test void checklistTargetMustExistAndMayOnlyPriceExtraMedia() {
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of());
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));
        when(checklistDefinitions.existsById("checklist-x")).thenReturn(true);
        var targeted = new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.PER_BLOCK,
                "X_IMAGE", "Checklist X image", BigDecimal.ZERO, null, new BigDecimal("50000"),
                null, null, BigDecimal.TEN, null, 0, true, "STANDARD",
                MediaPricingComponent.EXTRA_IMAGE, null, PricingRounding.CEILING, "checklist-x");

        assertThat(service.createDraft(request(targeted)).rules()).singleElement()
                .satisfies(rule -> assertThat(rule.checklistDefinitionId()).isEqualTo("checklist-x"));

        var unknown = new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.PER_BLOCK,
                "UNKNOWN", "Unknown", BigDecimal.ZERO, null, BigDecimal.ONE, null, null,
                BigDecimal.ONE, null, 0, true, "STANDARD", MediaPricingComponent.EXTRA_VIDEO,
                null, null, "missing");
        assertInvalid(request(unknown), "unknown checklist");
    }

    @Test void mediaRuleNeedsProfileAndComponentAndOtherCategoriesMustNotCarryThem() {
        var noProfile = new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.FIXED, "X", "x", null, null,
                BigDecimal.TEN, null, null, null, null, 0, true, null, MediaPricingComponent.INCLUDED_IMAGE, null, null);
        assertInvalid(request(noProfile), "pricing profile");
        var noComponent = new PricingRuleRequest(PricingCategory.MEDIA, PricingCalculationType.FIXED, "X", "x", null, null,
                BigDecimal.TEN, null, null, null, null, 0, true, "STANDARD", null, null, null);
        assertInvalid(request(noComponent), "media component");
        var leaked = new PricingRuleRequest(PricingCategory.OUTPUT, PricingCalculationType.FIXED, "VIDEO", "video", null, null,
                BigDecimal.TEN, null, null, null, null, 0, true, "STANDARD", MediaPricingComponent.INCLUDED_IMAGE, null, null);
        assertInvalid(request(leaked), "MEDIA rules only");
    }

    @Test void allowanceOnlyBelongsToTheCustomProfile() {
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of());
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(service.createDraft(request(included("A", MediaPricingComponent.INCLUDED_IMAGE, "0", 5, "custom"))).rules()).hasSize(1);
        assertInvalid(request(included("A", MediaPricingComponent.INCLUDED_IMAGE, "0", 5, "STANDARD")), "allowance");
    }

    @Test void publishedPoliciesAreImmutable() {
        PricingPolicy published = new PricingPolicy();
        published.setId("p1"); published.setStatus(PricingPolicyStatus.ACTIVE);
        when(repository.findById("p1")).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.updateDraft("p1", request(included("A", MediaPricingComponent.INCLUDED_IMAGE, "1", null, "STANDARD"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("immutable");
    }

    @Test void draftRulesCanBeUpdatedAndRemainOnThePersistedAggregate() {
        PricingPolicy draft = new PricingPolicy();
        draft.setId("draft-1"); draft.setStatus(PricingPolicyStatus.DRAFT); draft.setPolicyVersion(2);
        when(repository.findById("draft-1")).thenReturn(Optional.of(draft));

        var updated = service.updateDraft("draft-1", request(dimensionTier("AREA_UPDATED", "0", null)));

        assertThat(updated.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleKey()).isEqualTo("AREA_UPDATED");
            assertThat(rule.fixedAmount()).isEqualByComparingTo("1000");
            assertThat(rule.roundingMode()).isEqualTo(PricingRounding.CEILING);
        });
        assertThat(draft.getRules()).hasSize(1);
    }

    @Test void activePolicyIsDeepClonedIntoTheNextDraftVersion() {
        PricingPolicy active = new PricingPolicy();
        active.setId("active-1"); active.setName("Active pricing"); active.setPolicyVersion(4);
        active.setStatus(PricingPolicyStatus.ACTIVE); active.setCurrency("VND");
        active.setManagerMaxDiscountPercent(new BigDecimal("25"));
        active.setManagerMaxMarkupPercent(new BigDecimal("30"));
        active.setHighRiskDiscountPercent(new BigDecimal("10"));
        active.setMinimumPriceRatio(new BigDecimal("0.75"));
        active.setMinimumPrice(new BigDecimal("100000"));
        when(repository.findById("active-1")).thenReturn(Optional.of(active));
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of(active));
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));

        var cloned = service.cloneAsDraft("active-1");

        assertThat(cloned.status()).isEqualTo(PricingPolicyStatus.DRAFT);
        assertThat(cloned.version()).isEqualTo(5);
        assertThat(cloned.managerMaxMarkupPercent()).isEqualByComparingTo("30");
        assertThat(active.getStatus()).isEqualTo(PricingPolicyStatus.ACTIVE);
    }

    @Test void onlyAnActivePolicyCanBeCloned() {
        PricingPolicy draft = new PricingPolicy();
        draft.setId("draft-1"); draft.setStatus(PricingPolicyStatus.DRAFT);
        when(repository.findById("draft-1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.cloneAsDraft("draft-1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("active");
    }

    @Test void dimensionBlockRulesSupportRoundingAndRejectOverlappingTiers() {
        var first = dimensionTier("A1", "0", "10");
        var second = dimensionTier("A2", "10", null);
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of());
        when(repository.save(any(PricingPolicy.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(service.createDraft(request(first, second)).rules()).hasSize(2);

        assertInvalid(request(first, dimensionTier("A2", "5", null)), "overlap");
    }

    @Test void invalidOrdinaryRuleValuesAreRejected() {
        var noBlockSize = new PricingRuleRequest(PricingCategory.AREA, PricingCalculationType.PER_BLOCK,
                "AREA", "area", BigDecimal.ZERO, null, BigDecimal.TEN, null, null, null, null,
                0, true, null, null, null, PricingRounding.CEILING);
        assertInvalid(request(noBlockSize), "block size");
        var negativePercent = new PricingRuleRequest(PricingCategory.DISCOUNT,
                PricingCalculationType.PERCENT_OF_BASE, "DISCOUNT", "discount", null, null, null,
                null, new BigDecimal("-1"), null, null, 0, true, null, null, null, null);
        assertInvalid(request(negativePercent), "non-negative percentage");
    }

    PricingRuleRequest dimensionTier(String key, String from, String to) {
        return new PricingRuleRequest(PricingCategory.AREA, PricingCalculationType.PER_BLOCK, key, key,
                new BigDecimal(from), to == null ? null : new BigDecimal(to), new BigDecimal("1000"),
                null, null, BigDecimal.ONE, null, 0, true, null, null, null, PricingRounding.CEILING);
    }

    void assertInvalid(PricingPolicyRequest request, String fragment) {
        when(repository.findAllByOrderByPolicyVersionDesc()).thenReturn(new ArrayList<>());
        assertThatThrownBy(() -> service.createDraft(request)).isInstanceOf(ApiException.class)
                .hasMessageContaining(fragment);
    }
}
