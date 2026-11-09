package com.ondemandmonitoring.pricing.config;

import com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Fills {@code services.default_price} for services that have none yet. Idempotent; derived data only. */
@Slf4j
@Component
@Order(200) // after the catalog and pricing-policy seeders
@RequiredArgsConstructor
public class ServiceDefaultPriceBackfill implements ApplicationRunner {
    private final ServiceDefaultPriceService defaultPrices;

    @Override
    public void run(ApplicationArguments args) {
        int filled = defaultPrices.backfillMissing();
        if (filled > 0) log.info("Computed the stored default price of {} service(s)", filled);
    }
}
