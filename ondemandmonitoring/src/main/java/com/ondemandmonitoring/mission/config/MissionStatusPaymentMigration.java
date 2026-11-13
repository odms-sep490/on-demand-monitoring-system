package com.ondemandmonitoring.mission.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Converts the removed post-plan payment state before any runner loads Missions. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class MissionStatusPaymentMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        int migrated = jdbc.update(
                "UPDATE missions SET status = 'PENDING_CONFIRMATION' WHERE status = 'PENDING_PAYMENT'");
        if (migrated > 0) {
            log.info("Migrated {} missions from PENDING_PAYMENT to PENDING_CONFIRMATION", migrated);
        }
    }
}
