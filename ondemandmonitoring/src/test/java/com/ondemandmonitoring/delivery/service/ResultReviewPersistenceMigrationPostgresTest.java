package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in integration coverage against a disposable PostgreSQL database dedicated to this migration. */
@EnabledIfEnvironmentVariable(named = "ODMS_RESULT_REVIEW_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_result_review_verification")
class ResultReviewPersistenceMigrationPostgresTest {
    @Test void migrationAddsNullablePointerAndRoundConstraintsInDependencyOrder() throws Exception {
        String url = System.getenv("ODMS_RESULT_REVIEW_TEST_JDBC_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/odms_result_review_verification"))
            throw new IllegalStateException("Migration test URL must point to the dedicated local verification database");
        String schema = "result_review_verify_" + UUID.randomUUID().toString().replace("-", "");
        DataSource admin = dataSource(url);
        JdbcTemplate adminJdbc = new JdbcTemplate(admin);
        adminJdbc.execute("CREATE SCHEMA " + schema);
        try {
            String scopedUrl = url + "?currentSchema=" + schema;
            JdbcTemplate jdbc = new JdbcTemplate(dataSource(scopedUrl));
            jdbc.execute("CREATE TABLE users(id varchar(255) PRIMARY KEY)");
            jdbc.execute("CREATE TABLE orders(id varchar(255) PRIMARY KEY)");
            jdbc.execute("CREATE TABLE order_deliveries(id varchar(255) PRIMARY KEY, order_id varchar(255) NOT NULL, status varchar(40) NOT NULL)");
            jdbc.execute("CREATE TABLE support_customer_tickets(id varchar(255) PRIMARY KEY, status varchar(50) NOT NULL, opened_at timestamptz NOT NULL)");
            jdbc.execute("CREATE TABLE order_checklist_items(id varchar(255) PRIMARY KEY)");
            jdbc.execute("CREATE TABLE drone_media(id varchar(255) PRIMARY KEY)");

            executeScript(jdbc, migrationSql());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='order_deliveries' AND column_name='current_review_round_id'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='fk_order_delivery_current_review_round'", Integer.class)).isEqualTo(1);
            jdbc.update("INSERT INTO orders(id) VALUES ('legacy-order')");
            jdbc.update("INSERT INTO order_deliveries(id, order_id, status, current_review_round_id) VALUES ('legacy-delivery', 'legacy-order', 'CUSTOMER_REVIEW', NULL)");
            jdbc.update("INSERT INTO result_review_rounds(id, order_id, delivery_id, round_number, checklist_revision, published_at) VALUES ('round-1', 'legacy-order', 'legacy-delivery', 1, 1, NOW())");
            jdbc.update("UPDATE order_deliveries SET current_review_round_id='round-1' WHERE id='legacy-delivery'");
            assertThat(jdbc.queryForObject("SELECT current_review_round_id FROM order_deliveries WHERE id='legacy-delivery'", String.class)).isEqualTo("round-1");
            assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO result_review_rounds(id, order_id, delivery_id, round_number, checklist_revision, published_at) VALUES ('round-duplicate', 'legacy-order', 'legacy-delivery', 1, 1, NOW())"));
            assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO result_review_rounds(id, order_id, delivery_id, round_number, checklist_revision, published_at, status) VALUES ('round-invalid', 'legacy-order', 'legacy-delivery', 2, 1, NOW(), 'INVALID')"));
        } finally {
            adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static DataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        return dataSource;
    }

    private static String migrationSql() throws IOException {
        String bootstrap = new ClassPathResource("seeddata/migrations/bootstrap_after_hibernate.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String startMarker = "-- SECTION 8: Customer result review persistence.";
        String endMarker = "-- END CUSTOMER RESULT REVIEW";
        int start = bootstrap.indexOf(startMarker);
        int end = bootstrap.indexOf(endMarker);
        if (start < 0 || end <= start)
            throw new IllegalStateException("Customer result review section missing from bootstrap");
        return bootstrap.substring(start, end);
    }

    private static void executeScript(JdbcTemplate jdbc, String script) {
        for (String sql : statements(script)) jdbc.execute(sql);
    }

    /** Splits SQL at semicolons while preserving the migration's PostgreSQL DO $$ block. */
    private static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String dollarTag = null;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (dollarTag != null) {
                if (script.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else current.append(c);
                continue;
            }
            if (!singleQuoted && !doubleQuoted && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') i++;
                current.append('\n');
                continue;
            }
            if (!singleQuoted && !doubleQuoted && c == '$') {
                int end = script.indexOf('$', i + 1);
                if (end >= 0 && script.substring(i + 1, end).matches("[A-Za-z0-9_]*")) {
                    dollarTag = script.substring(i, end + 1);
                    current.append(dollarTag);
                    i = end;
                    continue;
                }
            }
            if (!doubleQuoted && c == '\'' && (i == 0 || script.charAt(i - 1) != '\\')) singleQuoted = !singleQuoted;
            else if (!singleQuoted && c == '"') doubleQuoted = !doubleQuoted;
            if (c == ';' && !singleQuoted && !doubleQuoted) {
                if (!current.toString().isBlank()) result.add(current.toString());
                current.setLength(0);
            } else current.append(c);
        }
        if (!current.toString().isBlank()) result.add(current.toString());
        return result;
    }
}
