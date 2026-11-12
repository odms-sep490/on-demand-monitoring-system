package com.ondemandmonitoring.devicecheck.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Backfills legacy device-connection links to the mission's assigned drone. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class PersistedPostDeviceCheckSchemaUpdater implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE IF EXISTS device_telemetry ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255)");
        jdbc.execute("ALTER TABLE IF EXISTS persisted_pre_device_check ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255)");
        jdbc.execute("ALTER TABLE IF EXISTS persisted_post_device_check ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255)");
        jdbc.execute("ALTER TABLE IF EXISTS control_handovers ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255)");
        jdbc.execute("""
                DO $$
                BEGIN
                  IF to_regclass('public.device_connections') IS NOT NULL THEN
                    IF to_regclass('public.device_telemetry') IS NOT NULL AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='device_telemetry' AND column_name='device_connection_id') THEN
                      EXECUTE 'ALTER TABLE device_telemetry ALTER COLUMN device_connection_id DROP NOT NULL';
                      UPDATE device_telemetry t SET mission_device_assignment_id = dc.mission_device_assignment_id
                      FROM device_connections dc WHERE t.device_connection_id = dc.id AND t.mission_device_assignment_id IS NULL;
                    END IF;
                    IF to_regclass('public.persisted_pre_device_check') IS NOT NULL AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='persisted_pre_device_check' AND column_name='device_connection_id') THEN
                      EXECUTE 'ALTER TABLE persisted_pre_device_check ALTER COLUMN device_connection_id DROP NOT NULL';
                      UPDATE persisted_pre_device_check c SET mission_device_assignment_id = dc.mission_device_assignment_id
                      FROM device_connections dc WHERE c.device_connection_id = dc.id AND c.mission_device_assignment_id IS NULL;
                    END IF;
                    IF to_regclass('public.persisted_post_device_check') IS NOT NULL AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='persisted_post_device_check' AND column_name='device_connection_id') THEN
                      EXECUTE 'ALTER TABLE persisted_post_device_check ALTER COLUMN device_connection_id DROP NOT NULL';
                      UPDATE persisted_post_device_check c SET mission_device_assignment_id = dc.mission_device_assignment_id
                      FROM device_connections dc WHERE c.device_connection_id = dc.id AND c.mission_device_assignment_id IS NULL;
                    END IF;
                    IF to_regclass('public.control_handovers') IS NOT NULL AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='control_handovers' AND column_name='device_connection_id') THEN
                      EXECUTE 'ALTER TABLE control_handovers ALTER COLUMN device_connection_id DROP NOT NULL';
                      UPDATE control_handovers h SET mission_device_assignment_id = dc.mission_device_assignment_id
                      FROM device_connections dc WHERE h.device_connection_id = dc.id AND h.mission_device_assignment_id IS NULL;
                    END IF;
                  END IF;
                END $$
                """);
        log.info("Migrated legacy device-connection references to mission drone assignments");
    }
}
