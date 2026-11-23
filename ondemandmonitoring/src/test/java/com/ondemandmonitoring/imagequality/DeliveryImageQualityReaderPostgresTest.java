package com.ondemandmonitoring.imagequality;

import static org.junit.jupiter.api.Assertions.*;

import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader.Signal;
import com.ondemandmonitoring.media.domain.MediaAsset;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named = "QUALITY_TEST_JDBC_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):.*")
class DeliveryImageQualityReaderPostgresTest {
    private static final Instant VALIDATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test void usesCurrentInternalSignalsAndNeverFallsBackToAnOlderAutomatedPass() throws Exception {
        String url = System.getenv("QUALITY_TEST_JDBC_URL");
        String username = System.getenv("QUALITY_TEST_DB_USER");
        String password = System.getenv("QUALITY_TEST_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        String schema = "suggestion_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + schema);
        try {
            String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            var source = new DriverManagerDataSource(schemaUrl, username, password);
            var db = new JdbcTemplate(source);
            var reader = new DeliveryImageQualityReader(new NamedParameterJdbcTemplate(db));
            var asset = new MediaAsset();
            asset.setId("image");
            asset.setValidatedAt(VALIDATED);
            asset.setChecksumSha256("AABB");
            assertEquals(Map.of(), reader.read("mission", List.of(asset)));

            db.execute("""
                    create table drone_media (
                        id varchar(255) primary key, mission_id varchar(255),
                        validated_at timestamptz, checksum_sha256 varchar(64))
                    """);
            db.update("insert into drone_media values ('image','mission',?,'AABB')", Timestamp.from(VALIDATED));
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("seeddata/migrations/add_image_quality.sql"));
            }
            check(db, "mission", "DETAIL", false, "COMPLETED", "1-provisional", "aabb", 1);
            var customer = check(db, "mission", "PANORAMA", true, "COMPLETED", "1-provisional", "aabb", 2);
            review(db, customer, "PASS", 25);
            check(db, "other-mission", "PANORAMA", false, "COMPLETED", "1-provisional", "aabb", 3);
            assertEquals(Map.of(), reader.read("mission", List.of(asset)));

            var stale = check(db, "mission", "PANORAMA", false, "COMPLETED", "1-provisional", "aabb", -1);
            assertEquals(new Signal(stale.toString(), null, null, null), signal(reader, asset));
            var pass = check(db, "mission", "PANORAMA", false, "COMPLETED", "1-provisional", "aabb", 4);
            assertEquals(new Signal(pass.toString(), null, "PASS", -1L), signal(reader, asset));
            var pending = check(db, "mission", "PANORAMA", false, "PENDING", "1-provisional", "aabb", 5);
            assertEquals(new Signal(pending.toString(), null, null, null), signal(reader, asset));
            var futureVersion = check(db, "mission", "PANORAMA", false, "COMPLETED", "2", "aabb", 6);
            assertEquals(new Signal(futureVersion.toString(), null, null, null), signal(reader, asset));
            var wrongChecksum = check(db, "mission", "PANORAMA", false, "COMPLETED", "1-provisional", "wrong", 7);
            assertEquals(new Signal(wrongChecksum.toString(), null, null, null), signal(reader, asset));

            var internal = check(db, "mission", "DETAIL", false, "COMPLETED", "1-provisional", "aabb", 8);
            review(db, internal, "PASS", 9);
            assertEquals(new Signal(internal.toString(), "PASS", null, -1L), signal(reader, asset));
            review(db, internal, "FAIL", 10);
            review(db, stale, "PASS", 11);
            check(db, "mission", "PANORAMA", false, "COMPLETED", "1-provisional", "aabb", 12);
            assertEquals(new Signal(internal.toString(), "FAIL", null, -1L), signal(reader, asset));
        } finally {
            admin.execute("drop schema " + schema + " cascade");
        }
    }

    private Signal signal(DeliveryImageQualityReader reader, MediaAsset asset) {
        return reader.read("mission", List.of(asset)).get(asset.getId());
    }

    private UUID check(JdbcTemplate db, String mission, String profile, boolean customer, String status,
            String version, String checksum, long offset) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(VALIDATED.plusSeconds(offset));
        String result = "COMPLETED".equals(status) ? """
                {"profile":"%s","profileVersion":"%s","decision":"PASS","algorithmVersion":"quality-metrics-1.0",
                 "metrics":{"checksumSha256":"%s","perceptualHash":"ffffffffffffffff"}}
                """.formatted(profile, version, checksum) : null;
        db.update("""
                insert into image_quality_check
                    (id, media_id, mission_id, requested_by, request_key, customer_request, profile, profile_version,
                     status, decision, result, created_at, started_at, completed_at)
                values (?, 'image', ?, 'actor', ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, mission, UUID.randomUUID(), customer, profile, version, status,
                result == null ? null : "PASS", result, at, result == null ? null : at, result == null ? null : at);
        return id;
    }

    private void review(JdbcTemplate db, UUID checkId, String decision, long offset) {
        db.update("""
                insert into image_quality_review (id, check_id, reviewer_id, decision, reviewed_at)
                values (?, ?, 'reviewer', ?, ?)
                """, UUID.randomUUID(), checkId, decision, Timestamp.from(VALIDATED.plusSeconds(offset)));
    }
}
