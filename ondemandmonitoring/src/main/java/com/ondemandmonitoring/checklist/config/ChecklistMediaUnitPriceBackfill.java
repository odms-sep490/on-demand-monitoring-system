package com.ondemandmonitoring.checklist.config;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Stores the JPA catalog defaults for existing checklists that do not have media rates yet. */
@Slf4j
@Component
@Order(160)
@RequiredArgsConstructor
public class ChecklistMediaUnitPriceBackfill implements ApplicationRunner {
    private final ChecklistDefinitionRepository definitions;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int updated = definitions.backfillMissingExtraMediaPrices(
                ChecklistDefinition.DEFAULT_EXTRA_IMAGE_UNIT_PRICE,
                ChecklistDefinition.DEFAULT_EXTRA_VIDEO_UNIT_PRICE);
        if (updated > 0) {
            log.info("Stored default extra media prices for {} checklist(s)", updated);
        }
    }
}
