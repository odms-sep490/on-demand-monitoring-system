package com.ondemandmonitoring.warehouse.config;

import com.ondemandmonitoring.warehouse.enums.PreferredTimeCode;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Updates the enum constraint on databases created before new time slots were added. */
@Component
@RequiredArgsConstructor
public class PreferredTimeSchemaUpdater implements ApplicationRunner, Ordered {
    private final DataSource dataSource;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
        }
        var values = Arrays.stream(PreferredTimeCode.values())
                .map(code -> "'" + code.name() + "'")
                .collect(Collectors.joining(", "));
        var jdbc = new JdbcTemplate(dataSource);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status ->
                jdbc.execute("ALTER TABLE preferred_times DROP CONSTRAINT IF EXISTS preferred_times_code_check, "
                        + "ADD CONSTRAINT preferred_times_code_check CHECK (code IN (" + values + "))"));
    }
}
