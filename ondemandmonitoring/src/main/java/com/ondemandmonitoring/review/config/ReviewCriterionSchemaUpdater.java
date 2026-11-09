package com.ondemandmonitoring.review.config;

import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
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

/** Keeps Hibernate's enum check constraint in sync when review criteria are added. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class ReviewCriterionSchemaUpdater implements CommandLineRunner {

    private static final String CONSTRAINT_NAME = "order_review_records_criterion_code_check";

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }

        String allowedValues = Arrays.stream(ReviewCriterionCode.values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE order_review_records DROP CONSTRAINT IF EXISTS " + CONSTRAINT_NAME);
        jdbc.execute("ALTER TABLE order_review_records ADD CONSTRAINT " + CONSTRAINT_NAME
                + " CHECK (criterion_code IN (" + allowedValues + "))");
        log.info("Synchronized order review criterion database constraint");
    }
}
