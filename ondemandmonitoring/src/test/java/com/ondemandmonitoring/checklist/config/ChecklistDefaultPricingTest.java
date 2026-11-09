package com.ondemandmonitoring.checklist.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChecklistDefaultPricingTest {
    @Test void everyBootstrapChecklistHasAPrice() {
        for (int number = 1; number <= ChecklistDefaults.CONTENTS.size(); number++) {
            var pricing = ChecklistDefaultPricing.forNumber(number);
            assertThat(pricing).as("checklist %d", number).isNotNull();
            assertThat(pricing.basePrice().signum()).isPositive();
            assertThat(pricing.includedImages() + pricing.includedVideos()).isPositive();
        }
    }

    @Test void constructionOverviewPhotoChecklistSeedsFourImages() {
        assertThat(ChecklistDefaultPricing.forNumber(5).includedImages()).isEqualTo(4);
        assertThat(ChecklistDefaultPricing.forNumber(20).includedImages()).isEqualTo(4);
        assertThat(ChecklistDefaultPricing.forNumber(21).includedVideos()).isEqualTo(1);
    }

    @Test void eachDefaultServiceTotalsItsFormerCatalogPrice() {
        Map<String, Long> expected = Map.of("Giám sát công trình", 3_500_000L, "Kiểm tra nhà xưởng", 3_000_000L,
                "Giám sát khu vực", 2_500_000L, "Giám sát rừng", 4_000_000L);
        ChecklistDefaults.templates().forEach((service, numbers) -> {
            BigDecimal total = numbers.stream().map(n -> ChecklistDefaultPricing.forNumber(n).basePrice())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total).as(service).isEqualByComparingTo(BigDecimal.valueOf(expected.get(service)));
        });
    }

    @Test void onlyUnpricedChecklistsAreSeededAndServicePricesAreRefreshed() {
        var repo = mock(ChecklistDefinitionRepository.class);
        var prices = mock(ServiceDefaultPriceService.class);
        var fresh = new ChecklistDefinition();
        var edited = new ChecklistDefinition();
        edited.setBasePrice(new BigDecimal("123000"));
        when(repo.findBySeedCode(ChecklistDefaults.code(1))).thenReturn(Optional.of(fresh));
        when(repo.findBySeedCode(ChecklistDefaults.code(2))).thenReturn(Optional.of(edited));
        when(prices.refreshUnpriced()).thenReturn(4);

        new ChecklistPricingSeedInitializer(repo, prices).run(null);

        assertThat(fresh.getBasePrice()).isEqualByComparingTo("800000");
        assertThat(fresh.getIncludedImageCount()).isEqualTo(10);
        assertThat(fresh.getIncludedVideoCount()).isEqualTo(1);
        assertThat(edited.getBasePrice()).isEqualByComparingTo("123000");
        verify(repo).save(fresh);
        verify(repo, never()).save(edited);
        verify(prices).refreshUnpriced();
    }
}
