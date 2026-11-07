package com.ondemandmonitoring.checklist.config;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives the bootstrap checklists their starting price and media allowance. Runs after the checklists, services and the
 * pricing policy exist and before the default-price backfill. Only completely unpriced checklists are seeded. The
 * previous construction photo allowance is migrated only when its seeded price and counts still match the old defaults.
 */
@Slf4j
@Component
@Order(150)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checklist.bootstrap-enabled", havingValue = "true", matchIfMissing = true)
public class ChecklistPricingSeedInitializer implements ApplicationRunner {
    private final ChecklistDefinitionRepository definitions;
    private final ServiceDefaultPriceService defaultPrices;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int priced = 0;
        for (int number = 1; number <= ChecklistDefaults.CONTENTS.size(); number++) {
            var pricing = ChecklistDefaultPricing.forNumber(number);
            if (pricing == null) continue;
            var definition = definitions.findBySeedCode(ChecklistDefaults.code(number)).orElse(null);
            if (definition == null || !untouched(definition)) continue;
            definition.setBasePrice(pricing.basePrice());
            definition.setIncludedImageCount(pricing.includedImages());
            definition.setIncludedVideoCount(pricing.includedVideos());
            definition.setStandardVideoDurationSeconds(pricing.videoSeconds());
            definitions.save(definition);
            priced++;
        }
        int migrated = migratePreviousConstructionPhotoAllowance();
        // These defaults are assigned after the checklist bootstrap and their service total is derived from them.
        for (int number : ChecklistDefaults.templates().get("Giám sát công trình")) {
            definitions.findBySeedCode(ChecklistDefaults.code(number))
                    .ifPresent(definition -> defaultPrices.refreshForChecklist(definition.getId()));
        }
        // Also repairs services whose stored default price was computed before any checklist had a price.
        int refreshed = defaultPrices.refreshUnpriced();
        if (priced > 0 || migrated > 0 || refreshed > 0)
            log.info("Seeded pricing for {} checklist(s), migrated {} photo allowance(s); recomputed the default price of {} service(s)",
                    priced, migrated, refreshed);
    }

    private int migratePreviousConstructionPhotoAllowance() {
        int migrated = 0;
        for (int number : new int[] {5, 20}) {
            var pricing = ChecklistDefaultPricing.forNumber(number);
            var definition = definitions.findBySeedCode(ChecklistDefaults.code(number)).orElse(null);
            if (definition == null || pricing == null
                    || definition.getIncludedImageCount() != 20
                    || definition.getIncludedVideoCount() != 0
                    || definition.getBasePrice() == null
                    || definition.getBasePrice().compareTo(pricing.basePrice()) != 0) continue;
            definition.setIncludedImageCount(pricing.includedImages());
            definitions.save(definition);
            migrated++;
        }
        return migrated;
    }

    static boolean untouched(ChecklistDefinition definition) {
        return (definition.getBasePrice() == null || definition.getBasePrice().signum() == 0)
                && definition.getIncludedImageCount() == 0 && definition.getIncludedVideoCount() == 0
                && definition.getStandardVideoDurationSeconds() == null && definition.getPricingProfile() == null;
    }
}
