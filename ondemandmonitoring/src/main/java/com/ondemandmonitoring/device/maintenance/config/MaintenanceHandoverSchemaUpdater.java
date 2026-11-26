package com.ondemandmonitoring.device.maintenance.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Upgraded PostgreSQL schemas keep NOT NULL on maintenance_handovers.to_user_id because Hibernate
 * ddl update never relaxes constraints. Pool handovers have no receiver until a Technical claims them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class MaintenanceHandoverSchemaUpdater implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }
        new JdbcTemplate(dataSource).execute("""
                DO $$
                BEGIN
                  IF to_regclass('maintenance_handovers') IS NOT NULL AND EXISTS (
                      SELECT 1 FROM pg_attribute
                      WHERE attrelid = to_regclass('maintenance_handovers')
                        AND attname = 'to_user_id'
                        AND NOT attisdropped
                        AND attnotnull
                  ) THEN
                    EXECUTE 'ALTER TABLE maintenance_handovers ALTER COLUMN to_user_id DROP NOT NULL';
                  END IF;
                END $$;
                """);
    }
}
