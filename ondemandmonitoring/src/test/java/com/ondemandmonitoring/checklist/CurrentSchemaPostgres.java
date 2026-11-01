package com.ondemandmonitoring.checklist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executes the canonical production SQL only inside a guarded disposable test schema. */
final class CurrentSchemaPostgres {
    private CurrentSchemaPostgres() {}

    static String sql(String filename, String schema) {
        if (!schema.matches("checklist_verify_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Unsafe verification schema");
        if (!filename.equals("bootstrap_after_hibernate.sql") && !filename.equals("verify_current_schema.sql"))
            throw new IllegalArgumentException("Only current schema resources are allowed");
        try {
            return new ClassPathResource("seeddata/migrations/" + filename)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("public.", schema + ".")
                    .replace("search_path = public, pg_catalog", "search_path = " + schema + ", public, pg_catalog");
        } catch (IOException exception) {
            throw new IllegalStateException("Current schema SQL unavailable", exception);
        }
    }

    static void bootstrap(DataSource dataSource, String schema) {
        new JdbcTemplate(dataSource).execute(sql("bootstrap_after_hibernate.sql", schema));
    }

    static void verify(DataSource dataSource, String schema) {
        new JdbcTemplate(dataSource).execute(sql("verify_current_schema.sql", schema));
    }
}
