package com.ondemandmonitoring.aiops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.prompt.PromptDtos.ResolvedPrompt;
import java.util.Set;
import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public static final String REQUEST_CONTEXT_ATTRIBUTE = ChecklistVisionRuntime.class.getName() + ".context";
    public static final String AUDIT_RECORDED_ATTRIBUTE = ChecklistVisionRuntime.class.getName() + ".recorded";
    private final ThreadLocal<RequestScope> requests = new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<ChecklistVisionIncidentService> incidents;

    /** Only opaque identifiers belong here. Discard invalid values rather than logging/truncating them. */
    public record RequestContext(String traceId, String actorId, String actorRole,
                                 String missionId, String executionId, String evidenceId) {
        public RequestContext {
            traceId = safe(traceId, 80);
            if (traceId == null) traceId = "AI-" + UUID.randomUUID();
            actorId = safe(actorId, 255);
            actorRole = safe(actorRole, 64);
            missionId = safe(missionId, 128);
            executionId = safe(executionId, 255);
            evidenceId = safe(evidenceId, 255);
        }
        public static RequestContext anonymous() { return new RequestContext(null, null, null, null, null, null); }
    }

    private static String safe(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Za-z0-9_.:-]+") ? value : null;
    }

    public static RequestContext httpContext() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute(REQUEST_CONTEXT_ATTRIBUTE) instanceof RequestContext context) {
            return context;
        }
        return RequestContext.anonymous();
    }

    public static String actorRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_")).sorted().map(a -> safe(a.substring(5), 64))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    public RequestScope currentRequest() { return requests.get(); }

    public RequestScope openRequest(RequestContext context, boolean playground) {
        if (requests.get() != null) throw new IllegalStateException("Checklist vision request already active");
        var scope = new RequestScope(context == null ? httpContext() : context, playground);
        requests.set(scope);
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            servlet.getRequest().setAttribute(REQUEST_CONTEXT_ATTRIBUTE, scope.context);
            if (servlet.getResponse() != null) servlet.getResponse().setHeader("X-Correlation-ID", scope.context.traceId());
        }
        return scope;
    }

    /** Check the live switch even if PromptManagementService has a cached ACTIVE version. Fail closed. */
    public void requirePromptEnabled() {
        boolean enabled = Boolean.TRUE.equals(db.query(
                "SELECT enabled FROM ai_prompts WHERE prompt_key = ?",
                rs -> rs.next() && rs.getBoolean(1), "checklist.image-vision.system"));
        if (!enabled) throw new com.ondemandmonitoring.common.exception.ApiException(
                com.ondemandmonitoring.common.exception.ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED);
    }

    public final class RequestScope implements AutoCloseable {
        private RequestContext context;
        private final boolean playground;
        private final long started = System.nanoTime();
        private String model;
        private ResolvedPrompt prompt;
        private String contentHash;
        private String source = "preflight";
        private String outcome = "ERROR";
        private String errorCode = "INTERNAL_SERVER_ERROR";
        private String decision = "ALLOW";
        private int status = 500;
        private boolean inferenceAttempted;
        private boolean closed;
        private boolean fallback;
        private boolean completed;
        private Integer durationOverride;

        private RequestScope(RequestContext context, boolean playground) {
            this.context = context;
            this.playground = playground;
        }
        public String traceId() { return context.traceId(); }
        public void authorized(String actorId, String missionId, String executionId, String evidenceId) {
            context = new RequestContext(context.traceId(), actorId, actorRole(), missionId, executionId, evidenceId);
        }
        public void pinnedPrompt(ResolvedPrompt prompt) { this.prompt = java.util.Objects.requireNonNull(prompt); }
        public void prompt(String model, ResolvedPrompt prompt, String instructions, boolean override) {
            if (prompt == null) prompt = this.prompt;
            this.model = model != null && model.length() <= 128 && model.matches("[A-Za-z0-9_./:-]+") ? model : null;
            this.prompt = prompt;
            source = prompt != null ? "checklist.image-vision.system" : override ? "playground" : "bundled";
            contentHash = prompt == null ? sha256Hex(instructions == null ? "" : instructions) : safe(prompt.contentHash(), 128);
            fallback = prompt == null ? !override : prompt.staleCacheUsed();
        }
        public void version(UUID version) { source = "playground"; playgroundVersion = version == null ? null : version.toString(); }
        private String playgroundVersion;
        public void inferenceStarted() { inferenceAttempted = true; }
        public void succeeded(String verdict) {
            completed = true;
            status = 200; errorCode = null; decision = "ALLOW";
            outcome = Set.of("MATCH", "MISMATCH", "UNCERTAIN").contains(verdict) ? verdict : "OK";
        }
        public void reject(int status, String code) {
            completed = true;
            this.status = status;
            errorCode = safe(code, 80);
            if (errorCode == null) errorCode = "INVALID_REQUEST";
            boolean infrastructure = status >= 500 && !"AI_RESPONSE_INVALID".equals(errorCode);
            decision = infrastructure ? "ALLOW" : "BLOCK";
            outcome = infrastructure ? "ERROR" : "BLOCKED";
        }
        public void finishHttp(int httpStatus) {
            if (httpStatus == 401 || httpStatus == 403) reject(httpStatus, httpStatus == 401 ? "UNAUTHORIZED" : "ACCESS_DENIED");
            else if (!completed || (httpStatus >= 400 && status < 400)) reject(httpStatus, "HTTP_" + httpStatus);
            if (httpStatus == 401 || httpStatus == 403) context = new RequestContext(context.traceId(),
                    context.actorId(), context.actorRole(), null, null, null);
        }
        public void failed(Throwable failure) {
            if (failure instanceof org.springframework.security.access.AccessDeniedException) {
                reject(403, "ACCESS_DENIED");
            } else if (failure instanceof org.springframework.security.core.AuthenticationException) {
                reject(401, "UNAUTHORIZED");
            } else if (failure instanceof com.ondemandmonitoring.common.exception.ApiException api) {
                reject(api.getStatus().value(), api.getErrorCode().name());
            } else if (failure instanceof ResponseStatusException response) {
                reject(response.getStatusCode().value(), "HTTP_" + response.getStatusCode().value());
            } else if (failure instanceof java.io.IOException) {
                reject(503, "AI_PROVIDER_UNAVAILABLE");
            } else {
                reject(500, "INTERNAL_SERVER_ERROR");
            }
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { persist(this, durationOverride != null ? durationOverride : (int) Math.min(Integer.MAX_VALUE,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))); }
            finally { requests.remove(); }
        }
    }

    private void persist(RequestScope scope, int duration) {
        String feature = scope.playground ? "CHECKLIST_VISION_PLAYGROUND" : "CHECKLIST_VISION";
        var context = scope.context;
        try {
            var spans = new java.util.ArrayList<java.util.Map<String, Object>>();
            var span = new java.util.LinkedHashMap<String, Object>();
            span.put("name", scope.source);
            span.put("durationMs", duration);
            span.put("status", scope.outcome);
            span.put("versionId", scope.prompt == null ? scope.playgroundVersion : safe(scope.prompt.versionId(), 128));
            span.put("contentHash", scope.contentHash);
            span.put("staleCacheUsed", scope.prompt != null && scope.prompt.staleCacheUsed());
            spans.add(span);
            if (scope.errorCode != null) spans.add(java.util.Map.of("name", "guardrail:" + scope.errorCode,
                    "durationMs", 0, "status", scope.outcome));
            String spansJson = json.writeValueAsString(spans);
            Runnable writes = () -> {
                db.update("""
                        INSERT INTO ai_execution_audits
                        (id, trace_id, user_id, user_role, model_code, model_version, prompt_version, feature,
                         resource_id, input_ref, output_ref, duration_ms, http_status, fallback_used,
                         validation_status, security_decision, inference_device, human_review_status,
                         spans_json, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, left(?, 64), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                                ?::jsonb, 0, now(), now())
                        """, UUID.randomUUID().toString(), context.traceId(), context.actorId(), context.actorRole(),
                        MODEL_CODE, scope.model, scope.prompt == null ? scope.source : "v" + scope.prompt.versionNumber(),
                        feature, context.missionId(), context.executionId(), context.evidenceId(), duration, scope.status,
                        scope.fallback, scope.status == 200 ? "PASSED" : scope.outcome,
                        scope.decision, scope.inferenceAttempted ? "Provider" : "None", spansJson);
                if ("BLOCK".equals(scope.decision)) {
                    db.update("""
                            INSERT INTO ai_security_events
                            (id, trace_id, event_type, risk_category, severity, user_id, feature, decision, summary,
                             version, created_at, updated_at)
                            VALUES (?, ?, 'CHECKLIST_VISION_GUARDRAIL', ?, 'MEDIUM', ?, ?, 'BLOCK', ?, 0, now(), now())
                            """, UUID.randomUUID().toString(), context.traceId(),
                            scope.inferenceAttempted ? "OUTPUT_VALIDATION" : "REQUEST_GUARDRAIL",
                            context.actorId(), feature, scope.errorCode == null ? "INVALID_REQUEST" : scope.errorCode);
                }
            };
            var dataSource = db.getDataSource();
            if (dataSource == null) throw new IllegalStateException("Checklist telemetry needs a DataSource");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setTimeout(3);
            transaction.executeWithoutResult(ignored -> writes.run());
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes != null) attributes.setAttribute(AUDIT_RECORDED_ATTRIBUTE, true, 0);

        } catch (Exception failure) {
            // Never include exception messages, prompts, provider content or identifiers supplied by clients.
            log.warn("Checklist vision telemetry transaction failed");
            return;
        }
        if (scope.inferenceAttempted && incidents != null) {
            try {
                var service = incidents.getIfAvailable();
                if (service != null) {
                    if (Set.of("AI_PROVIDER_UNAVAILABLE", "AI_RESPONSE_INVALID").contains(
                            scope.errorCode == null ? "" : scope.errorCode)) {
                        service.recordFailure(context.traceId(), scope.status, scope.errorCode);
                    } else if (scope.status == 200) service.recovered(context.traceId());
                }
            } catch (RuntimeException failure) {
                log.warn("Could not update checklist vision incident telemetry");
            }
        }

    }

    /** Compatibility entry point for existing callers and tools. */
    public void trace(String model, ResolvedPrompt prompt, String instructions, boolean playground, int status, int duration) {
        try (var scope = openRequest(RequestContext.anonymous(), playground)) {
            scope.durationOverride = Math.max(0, duration);
            scope.prompt(model, prompt, instructions, playground);
            scope.inferenceStarted();
            if (status == 200) scope.succeeded("OK");
            else scope.reject(status, "HTTP_" + status);
        }
    }
}
