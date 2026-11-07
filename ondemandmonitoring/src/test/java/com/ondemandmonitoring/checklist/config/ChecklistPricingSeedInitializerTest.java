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
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChecklistPricingSeedInitializerTest {
    @Test
    void migratesOnlyTheUnchangedPreviousConstructionPhotoSeeds() {
        var definitions = mock(ChecklistDefinitionRepository.class);
        var prices = mock(ServiceDefaultPriceService.class);
        var legacy = definition("S4005", 500_000, 20);
        var editedPrice = definition("S4020", 2_000_000, 20);
        when(definitions.findBySeedCode("S4005")).thenReturn(Optional.of(legacy));
        when(definitions.findBySeedCode("S4020")).thenReturn(Optional.of(editedPrice));

        new ChecklistPricingSeedInitializer(definitions, prices).run(null);

        assertThat(legacy.getIncludedImageCount()).isEqualTo(4);
        assertThat(editedPrice.getIncludedImageCount()).isEqualTo(20);
        verify(definitions).save(legacy);
        verify(definitions, never()).save(editedPrice);
    }

    private static ChecklistDefinition definition(String seedCode, long price, int images) {
        var definition = new ChecklistDefinition();
        definition.setSeedCode(seedCode);
        definition.setBasePrice(BigDecimal.valueOf(price));
        definition.setIncludedImageCount(images);
        definition.setIncludedVideoCount(0);
        return definition;
    }
}
