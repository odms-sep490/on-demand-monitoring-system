package com.ondemandmonitoring.aiops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.prompt.PromptDtos.ResolvedPrompt;
import java.util.Set;
import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistVisionRuntime {
    public static final String MODEL_CODE = "checklist-image-vision";
    private static final String ROW = """
            FROM ai_models m JOIN checklist_image_ai_settings s ON s.id = 'default'
            WHERE m.code = ?
            """;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public boolean enabled() {
        return db.query("SELECT m.enabled AND s.enabled " + ROW,
                rs -> rs.next() && rs.getBoolean(1), MODEL_CODE);
    }

    public Config config() {
        return db.query("SELECT m.enabled AND s.enabled, config_json::text " + ROW, rs -> {
            if (!rs.next() || !rs.getBoolean(1)) return new Config(false, null, null, null);
            try {
                return parse(json.readTree(rs.getString(2)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Invalid checklist vision registry configuration", e);
            }
        }, MODEL_CODE);
    }

    public record Config(boolean enabled, Double temperature, Integer maxTokens, Integer timeoutSec) {
        public static final Config DEFAULT = new Config(true, null, null, null);
    }

    public static Config parse(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("temperature", "maxTokens", "timeoutSec").contains(fields.next())) throw invalid();
        }
        var temp = node.get("temperature");
        if (temp != null && (!temp.isNumber() || !Double.isFinite(temp.doubleValue())
                || temp.doubleValue() < 0 || temp.doubleValue() > 2)) throw invalid();
        return new Config(true, temp == null ? null : temp.doubleValue(),
                integer(node.get("maxTokens"), 4096), integer(node.get("timeoutSec"), 90));
    }

    private static Integer integer(JsonNode value, int max) {
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > max) throw invalid();
        return value.intValue();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid checklist vision runtime configuration");
    }

    public void trace(String model, ResolvedPrompt prompt, String instructions, boolean playground, int status, int duration) {
        String source = playground ? "playground" : prompt == null ? "bundled" : prompt.promptKey();
        String id = UUID.randomUUID().toString();
        try {
            db.update("""
                    INSERT INTO ai_execution_audits
                    (id, trace_id, model_code, model_version, prompt_version, feature, duration_ms, http_status,
                     fallback_used, validation_status, security_decision, inference_device, human_review_status,
                     spans_json, version, created_at, updated_at)
                    VALUES (?, ?, ?, left(?, 64), ?, ?, ?, ?, ?, ?, 'ALLOW', 'Provider', 'PENDING',
                    jsonb_build_array(jsonb_build_object('name', ?::text, 'durationMs', ?::int, 'status', ?::text,
                    'versionId', ?::text, 'contentHash', ?::text, 'staleCacheUsed', ?::boolean)), 0, now(), now())
                    """, id, "AI-" + id, MODEL_CODE, model, prompt == null ? source : "v" + prompt.versionNumber(),
                    playground ? "CHECKLIST_VISION_PLAYGROUND" : "CHECKLIST_VISION", duration, status,
                    !playground && (prompt == null || prompt.staleCacheUsed()), status == 200 ? "PASSED" : "FAILED",
                    source, duration, status == 200 ? "OK" : "ERROR", prompt == null ? null : prompt.versionId(),
                    prompt == null ? sha256Hex(instructions) : prompt.contentHash(), prompt != null && prompt.staleCacheUsed());
        } catch (DataAccessException e) {
            log.warn("Could not persist checklist vision trace {}", id);
        }
    }
}
