package com.ondemandmonitoring.aiops;

import com.ondemandmonitoring.aiops.service.ChecklistVisionIncidentService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Uses a unique disposable schema, never application startup or Hibernate DDL. */
@EnabledIfEnvironmentVariable(named = "ODMS_TEST_VISION_JDBC_URL", matches = ".+")
class ChecklistVisionIncidentPostgresTest {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private ChecklistVisionIncidentService incidents;
    private String schema;

    @BeforeEach void setup() {
        String url = System.getenv("ODMS_TEST_VISION_JDBC_URL");
        var source = new DriverManagerDataSource(url);
        String user = System.getenv("ODMS_TEST_VISION_DB_USER");
        if (user != null) source.setUsername(user);
        String password = System.getenv("ODMS_TEST_VISION_DB_PASSWORD");
        if (password != null) source.setPassword(password);
        admin = new JdbcTemplate(source);
        schema = "vision_incident_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        if (user != null) scoped.setUsername(user);
        if (password != null) scoped.setPassword(password);
        db = new JdbcTemplate(scoped);
        db.execute("""
                CREATE TABLE ai_incidents (
                    id varchar(255) PRIMARY KEY, title varchar(200) NOT NULL,
                    severity varchar(24) NOT NULL, status varchar(24) NOT NULL,
                    model_code varchar(80), service_endpoint varchar(255), summary text NOT NULL,
                    recommended_action text, created_by varchar(255), resolved_by varchar(255),
                    resolved_at timestamptz, version bigint NOT NULL DEFAULT 0,
                    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz)
                """);
        incidents = new ChecklistVisionIncidentService(db);
    }

    @AfterEach void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void concurrentFailuresAcrossServiceInstancesProduceOneOpenIncident() throws Exception {
        var otherInstance = new ChecklistVisionIncidentService(db);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 24; i++) {
                int n = i;
                tasks.add(executor.submit(() -> {
                    start.await();
                    (n % 2 == 0 ? incidents : otherInstance).recordFailure("AI-" + n, 503, "AI_PROVIDER_UNAVAILABLE");
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertThat(count()).isEqualTo(1);
        var row = db.queryForMap("SELECT * FROM ai_incidents");
        assertThat(row.get("version")).isEqualTo(23L);
        assertThat(row.get("model_code")).isEqualTo("checklist-image-vision");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("summary").toString()).contains("Mã truy vết: AI-", "AI_PROVIDER_UNAVAILABLE");
        incidents.recovered("AI-concurrent-recovered");
        assertThat(db.queryForObject("SELECT status FROM ai_incidents", String.class)).isEqualTo("OPEN");
        db.update("UPDATE ai_incidents SET status='RESOLVED', resolved_by='admin', resolved_at=now()");
        incidents.recordFailure("AI-concurrent-new", 502, "AI_RESPONSE_INVALID");
        assertThat(count()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM ai_incidents WHERE status='OPEN'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT recommended_action FROM ai_incidents WHERE status='RESOLVED'", String.class))
                .contains("AI-concurrent-recovered");
    }

    @Test void ignoresNormalDenialsMismatchUnknownCodesAndUnsafeTraceReferences() {
        for (String code : new String[]{null, "", "MISMATCH", "AI_DISABLED", "FORBIDDEN", "secret provider body"})
            incidents.recordFailure("AI-safe", 503, code);
        for (String trace : new String[]{null, "", "Bearer secret / body", "<script>", "x".repeat(81)})
            incidents.recordFailure(trace, 503, "AI_PROVIDER_UNAVAILABLE");
        assertThat(count()).isZero();
    }

    @Test void malformedResponsesAggregateAndRecoveryRetainsAdminResolution() {
        incidents.recordFailure("AI-first", 502, "AI_RESPONSE_INVALID");
        incidents.recordFailure("AI-last", 504, "AI_PROVIDER_UNAVAILABLE");
        incidents.recovered("AI-recovery");
        var row = db.queryForMap("SELECT * FROM ai_incidents");
        assertThat(row.get("summary").toString()).contains("Mã truy vết: AI-last").doesNotContain("AI-first");
        assertThat(row.get("recommended_action").toString()).contains("Mã truy vết phục hồi: AI-recovery");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("resolved_at")).isNull();
        incidents.recordFailure("AI-again", 502, "AI_RESPONSE_INVALID");
        assertThat(db.queryForObject("SELECT recommended_action FROM ai_incidents", String.class))
                .doesNotContain("Mã truy vết phục hồi");
        // Existing admin resolution schema contract; a later outage creates a fresh incident.
        db.update("UPDATE ai_incidents SET status='RESOLVED', resolved_by='admin', resolved_at=now()");
        incidents.recordFailure("AI-new-outage", 503, "AI_PROVIDER_UNAVAILABLE");
        assertThat(count()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM ai_incidents WHERE status='RESOLVED' AND resolved_by='admin'", Integer.class)).isEqualTo(1);
    }

    @Test void recoveryNeverCreatesAnIncidentOrChangesManualIncidents() {
        incidents.recovered("AI-healthy");
        assertThat(count()).isZero();
        db.update("""
                INSERT INTO ai_incidents (id,title,severity,status,model_code,summary,created_by)
                VALUES ('manual','Manual incident','WARNING','OPEN','checklist-image-vision','manual evidence','admin')
                """);
        incidents.recordFailure("AI-down", 503, "AI_PROVIDER_UNAVAILABLE");
        incidents.recovered("AI-up");
        assertThat(count()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT summary FROM ai_incidents WHERE id='manual'", String.class)).isEqualTo("manual evidence");
        assertThat(db.queryForObject("SELECT version FROM ai_incidents WHERE id='manual'", Long.class)).isZero();
    }

    @Test void independentTransactionSurvivesCallerRollback() {
        var caller = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        caller.executeWithoutResult(tx -> {
            incidents.recordFailure("AI-rollback", 503, "AI_PROVIDER_UNAVAILABLE");
            tx.setRollbackOnly();
        });
        assertThat(count()).isEqualTo(1);
    }

    @Test void persistenceFailureDoesNotReplaceAssessmentResultOrPoisonCaller() {
        db.execute("DROP TABLE ai_incidents");
        var caller = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        assertThatCode(() -> caller.executeWithoutResult(tx -> {
            incidents.recordFailure("AI-db-down", 503, "AI_PROVIDER_UNAVAILABLE");
            incidents.recovered("AI-db-up");
            assertThat(db.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        })).doesNotThrowAnyException();
    }

    private int count() { return db.queryForObject("SELECT count(*) FROM ai_incidents", Integer.class); }
}
