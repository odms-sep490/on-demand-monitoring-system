package com.ondemandmonitoring.checklist.config;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService;
import java.math.BigDecimal;
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
    private static final BigDecimal PREVIOUS_FACTORY_CHECKLIST_PRICE = BigDecimal.valueOf(700_000);

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
        int defaultedAreas = applyDefaultAreaToPricedChecklists();
        int migrated = migratePreviousConstructionPhotoAllowance();
        // These defaults are assigned after the checklist bootstrap and their service total is derived from them.
        for (int number : ChecklistDefaults.templates().get("Giám sát công trình")) {
            definitions.findBySeedCode(ChecklistDefaults.code(number))
                    .ifPresent(definition -> defaultPrices.refreshForChecklist(definition.getId()));
        }
        // Also repairs services whose stored default price was computed before any checklist had a price.
        int refreshed = defaultPrices.refreshUnpriced();
        if (priced > 0 || defaultedAreas > 0 || migrated > 0 || refreshed > 0)
            log.info("Seeded pricing for {} checklist(s), set the 50 ha default area on {} checklist(s), "
                            + "migrated {} photo allowance(s); backfilled {} unpriced service(s)",
                    priced, defaultedAreas, migrated, refreshed);
    }

    /** Fill the 50 ha baseline only when no area was saved, preserving explicit area configurations. */
    private int applyDefaultAreaToPricedChecklists() {
        int updated = 0;
        for (ChecklistDefinition definition : definitions.findAll()) {
            if (definition.getStandardAreaM2() != null
                    || definition.getBasePrice() == null
                    || definition.getBasePrice().signum() <= 0) continue;

            if (ChecklistDefaults.code(7).equals(definition.getSeedCode())
                    && definition.getBasePrice().compareTo(PREVIOUS_FACTORY_CHECKLIST_PRICE) == 0) {
                definition.setBasePrice(ChecklistDefaultPricing.forNumber(7).basePrice());
            }
            definition.setStandardAreaM2(ChecklistDefaultPricing.DEFAULT_STANDARD_AREA_M2);
            definitions.save(definition);
            defaultPrices.refreshForChecklist(definition.getId());
            updated++;
        }
        return updated;
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
                && definition.getStandardAreaM2() == null && definition.getStandardVideoDurationSeconds() == null;
    }
}
