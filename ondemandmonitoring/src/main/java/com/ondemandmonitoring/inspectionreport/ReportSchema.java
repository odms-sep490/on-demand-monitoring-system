package com.ondemandmonitoring.inspectionreport;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component @RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="inspection-reports.initialize-schema",havingValue="true")
public class ReportSchema implements ApplicationRunner {
    private final JdbcTemplate db;
    public void run(ApplicationArguments args) throws Exception {
        try (var input = new ClassPathResource("seeddata/migrations/add_inspection_reports.sql").getInputStream()) {
            db.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        // Interrupted jobs are recovered by the bounded scheduler lease, including multi-JVM runs.
    }
}
