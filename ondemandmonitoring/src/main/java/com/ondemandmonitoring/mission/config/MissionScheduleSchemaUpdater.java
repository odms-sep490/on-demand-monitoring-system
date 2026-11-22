package com.ondemandmonitoring.mission.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures upgraded PostgreSQL schemas allow NULL scheduled_start_at and scheduled_end_at
 * on missions so unscheduled template, supplemental, and rework draft missions can be persisted
 * before the Manager finalizes the execution plan.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class MissionScheduleSchemaUpdater implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                DO $$
                BEGIN
                  IF to_regclass('missions') IS NOT NULL THEN
                    IF EXISTS (
                      SELECT 1 FROM pg_attribute
                      WHERE attrelid = to_regclass('missions')
                        AND attname = 'scheduled_start_at'
                        AND NOT attisdropped
                        AND attnotnull
                    ) THEN
                      EXECUTE 'ALTER TABLE missions ALTER COLUMN scheduled_start_at DROP NOT NULL';
                    END IF;
                    IF EXISTS (
                      SELECT 1 FROM pg_attribute
                      WHERE attrelid = to_regclass('missions')
                        AND attname = 'scheduled_end_at'
                        AND NOT attisdropped
                        AND attnotnull
                    ) THEN
                      EXECUTE 'ALTER TABLE missions ALTER COLUMN scheduled_end_at DROP NOT NULL';
                    END IF;
                  END IF;
                END $$
                """);
        log.info("Verified nullable schedule columns on missions table for unscheduled draft missions");
    }
}
