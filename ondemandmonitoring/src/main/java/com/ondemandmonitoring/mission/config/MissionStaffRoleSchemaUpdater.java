package com.ondemandmonitoring.mission.config;

import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate creates a CHECK constraint for the enum column only when the table is first
 * created; {@code ddl-auto=update} never widens it. Without this, assigning a newly added
 * role (e.g. TECHNICAL) fails with a constraint violation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class MissionStaffRoleSchemaUpdater implements CommandLineRunner {

    private static final String CONSTRAINT_NAME = "mission_staff_assignments_assigned_role_check";

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }
        String allowedValues = Arrays.stream(MissionStaffRole.values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE mission_staff_assignments DROP CONSTRAINT IF EXISTS " + CONSTRAINT_NAME);
        jdbc.execute("ALTER TABLE mission_staff_assignments ADD CONSTRAINT " + CONSTRAINT_NAME
                + " CHECK (assigned_role IS NULL OR assigned_role IN (" + allowedValues + "))");
        log.info("Synchronized mission staff role database constraint");
    }
}
