package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.aiops.config.AiModelSeedDataInitializer;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.TraceSpan;
import com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime;
import com.ondemandmonitoring.prompt.PromptDtos.ResolvedPrompt;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ODMS_TEST_VISION_JDBC_URL", matches = ".+")
class ChecklistVisionRuntimePostgresTest {
    @Test void readsSwitchesAndConfigAndPersistsPromptMetadata() throws Exception {
        try (var source = new SingleConnectionDataSource(System.getenv("ODMS_TEST_VISION_JDBC_URL"),
                System.getenv("ODMS_TEST_VISION_DB_USER"), System.getenv("ODMS_TEST_VISION_DB_PASSWORD"), true)) {
            var db = new JdbcTemplate(source);
            db.execute("""
                    create temporary table ai_models (id text, code text unique, name text, version text,
                    model_type text, capability text, source text, license text, checksum_sha256 text,
                    runtime text, device text, endpoint text, model_size text, status text, enabled boolean,
                    config_json jsonb, entity_version bigint, created_at timestamptz, updated_at timestamptz)
                    """);
            db.execute("create temporary table checklist_image_ai_settings (id text, enabled boolean)");
            db.execute("""
                    create temporary table ai_execution_audits (id text, trace_id text, model_code text,
                    user_id text, user_role text, resource_id text, input_ref text, output_ref text,
                    model_version varchar(64), prompt_version varchar(48), feature text, duration_ms int,
                    http_status int, fallback_used boolean, validation_status text, security_decision text,
                    inference_device text, human_review_status text, spans_json jsonb, version bigint,
                    created_at timestamptz, updated_at timestamptz)
                    """);
            db.execute("""
                    create temporary table ai_security_events (id text, trace_id text, event_type text,
                    risk_category text, severity text, user_id text, feature text, decision text,
                    summary text check (summary <> 'INVALID_REQUEST'), version bigint,
                    created_at timestamptz, updated_at timestamptz)
                    """);
            var mapper = new ObjectMapper();
            var runtime = new ChecklistVisionRuntime(db, mapper);
            var seed = new AiModelSeedDataInitializer(db);
            seed.run(null);
            assertThat(runtime.enabled()).isFalse();
            db.update("insert into checklist_image_ai_settings values ('default', true)");
            assertThat(runtime.enabled()).isTrue();
            db.update("update ai_models set enabled=false, config_json='{\"temperature\":0.5}' where code=?",
                    ChecklistVisionRuntime.MODEL_CODE);
            seed.run(null);
            assertThat(runtime.config().enabled()).isFalse();
            db.update("update ai_models set enabled=true where code=?", ChecklistVisionRuntime.MODEL_CODE);
            assertThat(runtime.config().temperature()).isEqualTo(0.5);
            runtime.trace("vision:model", new ResolvedPrompt("checklist.image-vision.system", "version-id", 3,
                    "hash", "private instructions", Map.of(), true), "private instructions", false, 200, 10);
            var row = db.queryForMap("select prompt_version, spans_json::text as spans from ai_execution_audits");
            assertThat(row.get("prompt_version")).isEqualTo("v3");
            String spans = row.get("spans").toString();
            assertThat(spans).doesNotContain("private instructions");
            var trace = mapper.readValue(spans, TraceSpan[].class)[0];
            assertThat(trace.versionId()).isEqualTo("version-id");
            assertThat(trace.contentHash()).isEqualTo("hash");
            assertThat(trace.staleCacheUsed()).isTrue();
            String correlation = "AI-" + java.util.UUID.randomUUID();
            try (var scope = runtime.openRequest(new ChecklistVisionRuntime.RequestContext(
                    correlation, "actor-id", "INSPECTOR", "mission-id", "execution-id", "evidence-id"), false)) {
                scope.reject(409, "CHECKLIST_IMAGE_ASSESSMENT_DISABLED");
            }
            var blocked = db.queryForMap("select * from ai_execution_audits where trace_id = ?", correlation);
            assertThat(blocked).containsEntry("user_id", "actor-id").containsEntry("user_role", "INSPECTOR")
                    .containsEntry("resource_id", "mission-id").containsEntry("input_ref", "execution-id")
                    .containsEntry("output_ref", "evidence-id").containsEntry("security_decision", "BLOCK")
                    .containsEntry("http_status", 409).containsEntry("inference_device", "None");
            assertThat(db.queryForObject("select count(*) from ai_security_events where trace_id = ?", Integer.class, correlation)).isEqualTo(1);
            // A security-event constraint failure rolls back its audit too; telemetry failure stays fail-soft.
            try (var scope = runtime.openRequest(ChecklistVisionRuntime.RequestContext.anonymous(), false)) {
                scope.reject(400, "INVALID_REQUEST");
            }
            assertThat(db.queryForObject("select count(*) from ai_execution_audits", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("select count(*) from ai_security_events", Integer.class)).isEqualTo(1);

        }
    }
}
