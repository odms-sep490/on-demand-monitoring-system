package com.ondemandmonitoring.pricing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.enums.MediaPricingComponent;
import com.ondemandmonitoring.pricing.enums.PricingCategory;
import com.ondemandmonitoring.pricing.enums.PricingPolicyStatus;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PricingPolicyInitializerTest {
    @Mock
    private PricingPolicyRepository policies;

    @Test
    void publishesANewVersionWhenLegacyActivePolicyHasNoDefaultMediaTiers() {
        PricingPolicy legacy = policy(3);
        when(policies.findFirstByStatusOrderByPolicyVersionDesc(PricingPolicyStatus.ACTIVE))
                .thenReturn(Optional.of(legacy));
        when(policies.findAllByOrderByPolicyVersionDesc()).thenReturn(List.of(legacy));

        new PricingPolicyInitializer(policies).run(null);

        ArgumentCaptor<PricingPolicy> saved = ArgumentCaptor.forClass(PricingPolicy.class);
        verify(policies, org.mockito.Mockito.times(2)).save(saved.capture());
        PricingPolicy repaired = saved.getAllValues().stream().filter(value -> value != legacy).findFirst().orElseThrow();
        assertThat(legacy.getStatus()).isEqualTo(PricingPolicyStatus.SUPERSEDED);
        assertThat(legacy.getEffectiveTo()).isNotNull();
        assertThat(repaired.getStatus()).isEqualTo(PricingPolicyStatus.ACTIVE);
        assertThat(repaired.getPolicyVersion()).isEqualTo(4);
        assertThat(repaired.getRules()).filteredOn(rule -> "DEFAULT".equals(rule.getPricingProfile())
                && rule.getMediaComponent() == MediaPricingComponent.EXTRA_IMAGE).hasSize(2);
        assertThat(repaired.getRules()).filteredOn(rule -> "DEFAULT".equals(rule.getPricingProfile())
                && rule.getMediaComponent() == MediaPricingComponent.EXTRA_VIDEO).hasSize(1);
    }

    @Test
    void leavesAnActivePolicyWithDefaultMediaTiersUntouched() {
        PricingPolicy active = policy(2);
        active.replaceRules(List.of(
                media("DEFAULT", MediaPricingComponent.EXTRA_IMAGE),
                media("DEFAULT", MediaPricingComponent.EXTRA_VIDEO),
                media("GENERIC_CUSTOMER_ADDED", MediaPricingComponent.CHECKLIST_BASE),
                media("GENERIC_CUSTOMER_ADDED", MediaPricingComponent.INCLUDED_IMAGE),
                media("GENERIC_CUSTOMER_ADDED", MediaPricingComponent.INCLUDED_AREA),
                media("GENERIC_CUSTOMER_ADDED", MediaPricingComponent.EXTRA_AREA)));
        when(policies.findFirstByStatusOrderByPolicyVersionDesc(PricingPolicyStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        new PricingPolicyInitializer(policies).run(null);

        verify(policies, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(active.getStatus()).isEqualTo(PricingPolicyStatus.ACTIVE);
    }

    private PricingPolicy policy(int version) {
        PricingPolicy policy = new PricingPolicy();
        policy.setName("Legacy policy");
        policy.setPolicyVersion(version);
        policy.setStatus(PricingPolicyStatus.ACTIVE);
        policy.setCurrency("VND");
        policy.setEffectiveFrom(Instant.parse("2026-01-01T00:00:00Z"));
        policy.setManagerMaxDiscountPercent(new BigDecimal("25"));
        policy.setManagerMaxMarkupPercent(new BigDecimal("25"));
        policy.setHighRiskDiscountPercent(new BigDecimal("10"));
        policy.setMinimumPriceRatio(new BigDecimal("0.75"));
        policy.setMinimumPrice(new BigDecimal("100000"));
        return policy;
    }

    private PricingRule media(String profile, MediaPricingComponent component) {
        PricingRule rule = new PricingRule();
        rule.setCategory(PricingCategory.MEDIA);
        rule.setPricingProfile(profile);
        rule.setMediaComponent(component);
        rule.setEnabled(true);
        return rule;
    }
}
