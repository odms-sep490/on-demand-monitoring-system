package com.ondemandmonitoring.mission.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MissionScheduleSchemaUpdaterTest {

    @Test
    void dropsLegacyNotNullConstraintsOnMissionScheduleColumnsForPostgresUpgrades() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        java.sql.Statement statement = mock(java.sql.Statement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.createStatement()).thenReturn(statement);

        MissionScheduleSchemaUpdater updater = new MissionScheduleSchemaUpdater(dataSource);
        updater.run();

        verify(statement).execute(contains("ALTER TABLE missions ALTER COLUMN scheduled_start_at DROP NOT NULL"));
        verify(statement).execute(contains("ALTER TABLE missions ALTER COLUMN scheduled_end_at DROP NOT NULL"));
    }

    @Test
    void skipsMigrationOnNonPostgresDatabases() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        MissionScheduleSchemaUpdater updater = new MissionScheduleSchemaUpdater(dataSource);
        updater.run();

        verify(connection, never()).createStatement();
    }

    @Test
    void bootstrapAndVerifyScriptsIncludeUnscheduledMissionColumnInvariants() throws Exception {
        String bootstrap = new ClassPathResource("seeddata/migrations/bootstrap_after_hibernate.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String verify = new ClassPathResource("seeddata/migrations/verify_current_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String migration = new ClassPathResource("seeddata/migrations/add_unscheduled_template_missions.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(bootstrap)
                .contains("ALTER TABLE public.missions")
                .contains("ALTER COLUMN scheduled_start_at DROP NOT NULL")
                .contains("ALTER COLUMN scheduled_end_at DROP NOT NULL");
        assertThat(migration)
                .contains("ALTER COLUMN scheduled_start_at DROP NOT NULL")
                .contains("ALTER COLUMN scheduled_end_at DROP NOT NULL");
        assertThat(verify)
                .contains("attname='scheduled_start_at' AND NOT attisdropped AND NOT attnotnull")
                .contains("attname='scheduled_end_at' AND NOT attisdropped AND NOT attnotnull");
    }
}
