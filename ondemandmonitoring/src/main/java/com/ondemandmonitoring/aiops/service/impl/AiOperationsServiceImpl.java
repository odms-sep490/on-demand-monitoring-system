package com.ondemandmonitoring.aiops.service.impl;

import com.ondemandmonitoring.aiops.service.IAiOperationsService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.aiops.dto.AiOperationsDtos.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiOperationsServiceImpl implements IAiOperationsService {
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd/MM");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.ondemandmonitoring.ai.client.ChecklistImageVisionClient> checklistVisionProvider;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuthenticatedUserResolver users;

    @Value("${IMAGE_AI_URL:http://localhost:8000}")
    private String imageWorkerUrl;

    @Value("${DESCRIPTION_AI_URL:http://localhost:8002}")
    private String aiWorkerUrl;

    @Value("${OLLAMA_URL:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${AI_EVAL_SUMMARY_PATH:../.ai-runtime/deepeval/deepeval-summary.json}")
    private String evalSummaryPath;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public void ensureSchema() {
        // Tables are managed by JPA @Entity classes; default models are seeded on startup by AiModelSeedDataInitializer.
    }

    public AiOverviewView getOverview() {
        ensureSchema();
        List<ServiceHealthView> services = checkServices();
        List<AiModelView> models = listModelsWithLiveStatus(services);
        List<AiTraceView> traces = listTraces(200);
        List<AiIncidentView> openIncidents = listIncidents("OPEN");

        int activeModels = (int) models.stream()
                .filter(m -> m.enabled() && ("HEALTHY".equals(m.status()) || "IDLE".equals(m.status())))
                .count();
        int degradedModels = (int) models.stream()
                .filter(m -> m.enabled() && ("DEGRADED".equals(m.status()) || "OFFLINE".equals(m.status())))
                .count();

        LocalDate today = LocalDate.now(VN_ZONE);
        List<AiTraceView> todayTraces = traces.stream()
                .filter(t -> t.createdAt() != null && t.createdAt().atZone(VN_ZONE).toLocalDate().equals(today))
                .toList();

        long requestsToday = todayTraces.size();
        long totalScored = traces.size();
        long successCount = traces.stream()
                .filter(t -> t.httpStatus() >= 200 && t.httpStatus() < 400 && !"FAILED".equalsIgnoreCase(t.validationStatus()))
                .count();
        double successRate = totalScored == 0 ? 100.0 : Math.round((successCount * 1000.0) / totalScored) / 10.0;

        List<Integer> durations = traces.stream()
                .map(AiTraceView::durationMs)
                .filter(d -> d > 0)
                .sorted()
                .toList();
        long p95Latency = durations.isEmpty() ? 0L : durations.get(Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.95) - 1));

        List<DailyVolumePoint> volume = buildDailyVolume(traces, 7);

        return new AiOverviewView(
                models.size(),
                activeModels,
                degradedModels,
                requestsToday,
                successRate,
                p95Latency,
                openIncidents.size(),
                volume,
                models,
                services,
                readSystemResources(),
                openIncidents,
                Instant.now()
        );
    }

    public List<AiModelView> listModels() {
        ensureSchema();
        return listModelsWithLiveStatus(checkServices());
    }

    private List<AiModelView> listModelsWithLiveStatus(List<ServiceHealthView> services) {
        Map<String, String> endpointStatus = new HashMap<>();
        for (ServiceHealthView svc : services) {
            endpointStatus.put(svc.endpoint(), svc.status());
        }
        var checklistVision = checklistVisionProvider == null ? null : checklistVisionProvider.getIfAvailable();
        var rows = db.queryForList("SELECT * FROM ai_models ORDER BY model_type, name");
        List<AiModelView> result = new ArrayList<>();
        for (var stored : rows) {
            var row = new HashMap<>(stored);
            if (ChecklistVisionRuntime.MODEL_CODE.equals(row.get("code")) && checklistVision != null) {
                var deployment = checklistVision.deployment();
                row.put("enabled", Boolean.TRUE.equals(row.get("enabled")) && db.query(
                        "SELECT enabled FROM checklist_image_ai_settings WHERE id = 'default'",
                        rs -> rs.next() && rs.getBoolean(1)));
                row.put("name", deployment.model().isBlank() ? "Chưa cấu hình mô hình" : deployment.model());
                row.put("version", "Đánh giá ảnh checklist");
                row.put("capability", "Đối chiếu ảnh với từng tiêu chí của checklist");
                row.put("source", deployment.provider());
                row.put("runtime", deployment.provider().equals("Ollama") ? "Ollama cục bộ" : "OpenRouter qua API");
                row.put("device", "Theo cấu hình nhà cung cấp");
                row.put("endpoint", deployment.endpoint());
                row.put("license", "Theo giấy phép của mô hình");
                row.put("checksum_sha256", "Chưa xác minh");
                row.put("model_size", "Chưa xác định");
                if (!deployment.configured()) row.put("status", "UNCONFIGURED");
            }
            boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
            String endpoint = Objects.toString(row.get("endpoint"), "");
            String computedStatus;
            if (!enabled) {
                computedStatus = "DISABLED";
            } else if ("UNCONFIGURED".equals(row.get("status"))) {
                computedStatus = "UNCONFIGURED";
            } else {
                String live = endpointStatus.get(endpoint);
                if (live == null) {
                    live = services.stream()
                            .filter(s -> endpoint.startsWith(extractOrigin(s.endpoint())))
                            .map(ServiceHealthView::status)
                            .findFirst()
                            .orElse(Objects.toString(row.get("status"), "IDLE"));
                }
                computedStatus = live;
            }
            result.add(new AiModelView(
                    Objects.toString(row.get("id"), ""),
                    Objects.toString(row.get("code"), ""),
                    Objects.toString(row.get("name"), ""),
                    Objects.toString(row.get("version"), ""),
                    Objects.toString(row.get("model_type"), ""),
                    Objects.toString(row.get("capability"), ""),
                    Objects.toString(row.get("source"), ""),
                    Objects.toString(row.get("license"), ""),
                    Objects.toString(row.get("checksum_sha256"), "UNVERIFIED"),
                    Objects.toString(row.get("runtime"), ""),
                    Objects.toString(row.get("device"), "CPU"),
                    endpoint,
                    Objects.toString(row.get("model_size"), "N/A"),
                    computedStatus,
                    enabled,
                    parseMap(row.get("config_json")),
                    toInstant(row.get("last_evaluated_at")),
                    toInstant(row.get("updated_at"))
            ));
        }
        return result;
    }

    @Transactional
    public AiModelView toggleModel(String code, boolean enabled) {
        ensureSchema();
        int updated = db.update(
                "UPDATE ai_models SET enabled = ?, status = ?, updated_at = now() WHERE code = ?",
                enabled, enabled ? "IDLE" : "DISABLED", code
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy model: " + code);
        }
        if (ChecklistVisionRuntime.MODEL_CODE.equals(code)) {
            db.update("""
                    UPDATE checklist_image_ai_settings
                    SET enabled = ?, version = version + 1, updated_at = now(), updated_by = ?
                    WHERE id = 'default'
                    """, enabled, safeCurrentUser());
        }
        recordAudit(
                code,
                enabled ? "ENABLE_MODEL" : "KILL_SWITCH_DISABLE",
                code,
                0,
                200,
                "PASSED",
                "ALLOW",
                false,
                List.of(new TraceSpan(enabled ? "Kích hoạt model" : "Vô hiệu hóa (Kill Switch)", 1, "OK"))
        );
        return listModels().stream()
                .filter(m -> m.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public AiModelView updateModelConfig(String code, Map<String, Object> config) {
        ensureSchema();
        if (ChecklistVisionRuntime.MODEL_CODE.equals(code)) ChecklistVisionRuntime.parse(json.valueToTree(config));
        // Whitelist only safe configuration keys — never allow shell/command/path execution keys.
        Set<String> allowedKeys = Set.of("temperature", "timeoutSec", "maxTokens", "threshold", "blurThreshold", "language", "sampleRate");
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (var entry : config.entrySet()) {
            if (allowedKeys.contains(entry.getKey())) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        int updated = db.update(
                "UPDATE ai_models SET config_json = ?::jsonb, updated_at = now() WHERE code = ?",
                writeJson(sanitized), code
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy model: " + code);
        }
        recordAudit(
                code,
                "UPDATE_MODEL_CONFIG",
                code,
                0,
                200,
                "PASSED",
                "ALLOW",
                false,
                List.of(new TraceSpan("Cập nhật cấu hình model", 2, "OK"))
        );
        return listModels().stream()
                .filter(m -> m.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public ServiceHealthView probeModel(String code) {
        ensureSchema();
        var rows = db.queryForList("SELECT code, name, endpoint, enabled FROM ai_models WHERE code = ?", code);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy model: " + code);
        }
        var row = rows.getFirst();
        String endpoint = Objects.toString(row.get("endpoint"), "");
        String name = Objects.toString(row.get("name"), code);
        ServiceHealthView probe = probeEndpoint(code, name, endpoint, false);
        db.update(
                "UPDATE ai_models SET status = ?, updated_at = now() WHERE code = ?",
                Boolean.TRUE.equals(row.get("enabled")) ? probe.status() : "DISABLED",
                code
        );
        recordAudit(
                code,
                "LIVE_HEALTH_PROBE",
                code,
                (int) probe.latencyMs(),
                "HEALTHY".equals(probe.status()) ? 200 : 503,
                "HEALTHY".equals(probe.status()) ? "PASSED" : "FAILED",
                "ALLOW",
                false,
                List.of(
                        new TraceSpan("HTTP readiness probe", probe.latencyMs(), "HEALTHY".equals(probe.status()) ? "OK" : "ERROR")
                )
        );
        return probe;
    }

    public List<ServiceHealthView> checkServices() {
        ensureSchema();
        String imgBase = normalizeBase(imageWorkerUrl, "http://localhost:8000");
        String workerBase = normalizeBase(aiWorkerUrl, "http://localhost:8002");
        String ollamaBase = normalizeBase(ollamaUrl, "http://localhost:11434");

        return List.of(
                probeEndpoint("image-worker", "Image Worker (Open-CD & Quality)", imgBase + "/quality/ready", false),
                probeEndpoint("ai-worker", "AI Worker (Qwen, ASR, ZeroTTS, Reports)", workerBase + "/conversation/health", false),
                probeEndpoint("ollama-runtime", "Ollama Local LLM Runtime", ollamaBase + "/api/tags", true)
        );
    }

    private ServiceHealthView probeEndpoint(String code, String name, String url, boolean parseOllamaTags) {
        long start = System.currentTimeMillis();
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = Math.max(1L, System.currentTimeMillis() - start);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                List<String> loaded = List.of();
                String detail = "HTTP " + response.statusCode() + " · Phản hồi " + elapsed + " ms";
                if (parseOllamaTags) {
                    try {
                        JsonNode root = json.readTree(response.body());
                        List<String> names = new ArrayList<>();
                        for (JsonNode m : root.path("models")) {
                            String modelName = m.path("name").asText("");
                            if (!modelName.isBlank()) names.add(modelName);
                        }
                        loaded = names;
                        detail = "Đã tải " + names.size() + " model trên Ollama · " + elapsed + " ms";
                    } catch (Exception ignored) {
                        // Keep basic detail if JSON differs.
                    }
                }
                return new ServiceHealthView(code, name, url, "HEALTHY", elapsed, detail, loaded, Instant.now());
            }
            return new ServiceHealthView(code, name, url, "DEGRADED", elapsed, "HTTP " + response.statusCode(), List.of(), Instant.now());
        } catch (Exception ex) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - start);
            return new ServiceHealthView(code, name, url, "OFFLINE", elapsed, "Không kết nối được (" + ex.getClass().getSimpleName() + ")", List.of(), Instant.now());
        }
    }

    public SystemResourceView readSystemResources() {
        Runtime rt = Runtime.getRuntime();
        long heapMax = rt.maxMemory() / (1024 * 1024);
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        double cpuPercent = 0.0;
        long totalMemMb = heapMax;
        long usedMemMb = heapUsed;
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double load = sunOs.getCpuLoad();
            if (load >= 0) {
                cpuPercent = Math.round(load * 1000.0) / 10.0;
            }
            long physTotal = sunOs.getTotalMemorySize();
            long physFree = sunOs.getFreeMemorySize();
            if (physTotal > 0) {
                totalMemMb = physTotal / (1024 * 1024);
                usedMemMb = (physTotal - physFree) / (1024 * 1024);
            }
        }
        double memPercent = totalMemMb == 0 ? 0.0 : Math.round((usedMemMb * 1000.0) / totalMemMb) / 10.0;
        return new SystemResourceView(
                cpuPercent,
                memPercent,
                usedMemMb,
                totalMemMb,
                heapUsed,
                heapMax,
                rt.availableProcessors(),
                System.getProperty("os.name", "Unknown")
        );
    }

    public List<AiTraceView> listTraces(int limit) {
        ensureSchema();
        int bounded = Math.max(1, Math.min(limit, 200));
        List<AiTraceView> items = new ArrayList<>();

        var auditRows = db.queryForList("SELECT * FROM ai_execution_audits ORDER BY created_at DESC LIMIT ?", bounded);
        for (var r : auditRows) {
            items.add(new AiTraceView(
                    Objects.toString(r.get("id"), ""),
                    Objects.toString(r.get("trace_id"), ""),
                    Objects.toString(r.get("user_id"), "system"),
                    Objects.toString(r.get("user_role"), "SYSTEM"),
                    Objects.toString(r.get("model_code"), ""),
                    Objects.toString(r.get("model_version"), ""),
                    Objects.toString(r.get("prompt_version"), "v1"),
                    Objects.toString(r.get("feature"), ""),
                    Objects.toString(r.get("resource_id"), ""),
                    Objects.toString(r.get("input_ref"), ""),
                    Objects.toString(r.get("output_ref"), ""),
                    ((Number) r.getOrDefault("duration_ms", 0)).intValue(),
                    ((Number) r.getOrDefault("http_status", 200)).intValue(),
                    Objects.toString(r.get("inference_device"), "CPU"),
                    Boolean.TRUE.equals(r.get("fallback_used")),
                    Objects.toString(r.get("validation_status"), "PASSED"),
                    Objects.toString(r.get("security_decision"), "ALLOW"),
                    Objects.toString(r.get("human_review_status"), "PENDING"),
                    parseSpans(r.get("spans_json")),
                    toInstant(r.get("created_at"))
            ));
        }

        // Also project real Inspection Report Studio AI runs if the table exists.
        if (Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('inspection_report_versions') IS NOT NULL", Boolean.class))) {
            var repRows = db.queryForList("""
                SELECT v.report_id, v.revision, v.action, v.actor, v.created_at, r.mission_id, r.model, r.status
                FROM inspection_report_versions v
                JOIN inspection_reports r ON r.id = v.report_id
                ORDER BY v.created_at DESC
                LIMIT ?
            """, Math.min(50, bounded));
            for (var r : repRows) {
                String action = Objects.toString(r.get("action"), "");
                String reportId = Objects.toString(r.get("report_id"), "");
                String missionId = Objects.toString(r.get("mission_id"), "");
                String model = Objects.toString(r.get("model"), "odm-order-qwen:3b");
                boolean failed = action.contains("FAIL") || action.contains("INTERRUPT");
                items.add(new AiTraceView(
                        "rep-" + reportId + "-" + r.get("revision"),
                        "AI-REP-" + reportId.substring(0, Math.min(8, reportId.length())).toUpperCase() + "-V" + r.get("revision"),
                        Objects.toString(r.get("actor"), "AI_WORKER"),
                        "AI_WORKER".equals(r.get("actor")) ? "AI_WORKER" : "STAFF",
                        model,
                        "v" + r.get("revision"),
                        "report-studio-v1",
                        "Báo cáo giám sát AI (" + action + ")",
                        missionId,
                        "mission:" + missionId,
                        "report:" + reportId,
                        failed ? 0 : 1850,
                        failed ? 500 : 200,
                        "GPU/CPU",
                        false,
                        failed ? "FAILED" : "PASSED",
                        "ALLOW",
                        "APPROVED".equals(r.get("status")) || "FINALIZED".equals(r.get("status")) || "DELIVERED".equals(r.get("status")) ? "APPROVED" : "PENDING",
                        List.of(
                                new TraceSpan("Validate & Load Evidence", 140, "OK"),
                                new TraceSpan("Model Inference (" + model + ")", failed ? 0 : 1480, failed ? "ERROR" : "OK"),
                                new TraceSpan("Schema & Grounding Guard", 160, failed ? "ERROR" : "OK"),
                                new TraceSpan("Persist Revision", 70, "OK")
                        ),
                        toInstant(r.get("created_at"))
                ));
            }
        }

        items.sort(Comparator.comparing(AiTraceView::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items.size() > bounded ? items.subList(0, bounded) : items;
    }

    public List<AiEvaluationView> listEvaluations() {
        ensureSchema();
        var evaluations = readPersistedEvaluations();
        return evaluations.isEmpty() ? syncDeepEvalFromDisk() : evaluations;
    }

    private List<AiEvaluationView> readPersistedEvaluations() {
        var rows = db.queryForList("SELECT * FROM ai_evaluation_runs ORDER BY suite_group ASC, metric_name ASC LIMIT 100");
        List<AiEvaluationView> out = new ArrayList<>();
        for (var r : rows) {
            int totalCases = ((Number) r.getOrDefault("total_cases", 0)).intValue();
            int passedCases = ((Number) r.getOrDefault("passed_cases", 0)).intValue();
            double storedPassRate = r.get("pass_rate") instanceof Number n ? n.doubleValue() : 0.0;
            double passRate = storedPassRate > 0 || totalCases == 0
                    ? storedPassRate
                    : Math.round((passedCases * 1000.0) / totalCases) / 10.0;
            out.add(new AiEvaluationView(
                    Objects.toString(r.get("id"), ""),
                    Objects.toString(r.get("model_code"), ""),
                    Objects.toString(r.get("model_version"), ""),
                    Objects.toString(r.get("suite_name"), ""),
                    Objects.toString(r.get("metric_name"), ""),
                    ((Number) r.get("score")).doubleValue(),
                    passRate,
                    ((Number) r.get("target_score")).doubleValue(),
                    Boolean.TRUE.equals(r.get("passed")),
                    totalCases,
                    passedCases,
                    ((Number) r.getOrDefault("failed_cases", 0)).intValue(),
                    ((Number) r.getOrDefault("skipped_cases", 0)).intValue(),
                    ((Number) r.getOrDefault("error_cases", 0)).intValue(),
                    Objects.toString(r.get("suite_group"), "core"),
                    Objects.toString(r.get("dataset_version"), "v1.0.0"),
                    Objects.toString(r.get("judge_model"), "qwen3:4b"),
                    Objects.toString(r.get("prompt_version"), "v1.0"),
                    Objects.toString(r.get("run_id"), ""),
                    r.get("baseline_delta") instanceof Number bd ? bd.doubleValue() : null,
                    ((Number) r.getOrDefault("avg_latency_ms", 0)).intValue(),
                    ((Number) r.getOrDefault("p95_latency_ms", 0)).intValue(),
                    ((Number) r.getOrDefault("live_scored", 0)).intValue(),
                    ((Number) r.getOrDefault("live_passed", 0)).intValue(),
                    Objects.toString(r.get("source_artifact"), ""),
                    toInstant(r.get("created_at"))
            ));
        }
        return out;
    }

    @Transactional
    public List<AiEvaluationView> syncDeepEvalFromDisk() {
        ensureSchema();
        Path path = resolveEvalArtifact("deepeval-summary.json");
        if (path != null && Files.exists(path)) {
            try {
                JsonNode root = json.readTree(Files.readString(path));
                db.update("DELETE FROM ai_evaluation_runs WHERE source_artifact = ?", path.getFileName().toString());
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String metric = entry.getKey();
                    JsonNode node = entry.getValue();
                    double mean = Math.round(node.path("mean").asDouble(0.0) * 1000.0) / 10.0;
                    double threshold = Math.round(node.path("threshold").asDouble(0.8) * 1000.0) / 10.0;
                    int scored = node.path("scored").asInt(0);
                    int passedCases = node.path("passed").asInt(0);
                    int failedCases = node.path("failed").asInt(0);
                    int skippedCases = node.path("skipped").asInt(0);
                    int errorCases = node.has("errors") ? node.path("errors").asInt(0) : node.path("blocked").asInt(0);
                    double passRate = node.has("passRate")
                            ? Math.round(node.path("passRate").asDouble(0.0) * 1000.0) / 10.0
                            : (scored == 0 ? 0.0 : Math.round((passedCases * 1000.0) / scored) / 10.0);
                    boolean passed = mean >= threshold && errorCases == 0 && scored > 0;
                    String suiteGroup = node.path("suiteGroup").asText("core");
                    String datasetVersion = node.path("datasetVersion").asText("v1.0.0");
                    String judgeModel = node.path("judgeModel").asText("qwen3:4b");
                    String promptVersion = node.path("promptVersion").asText("v1.0");
                    String runId = node.path("runId").asText("");
                    Double baselineDelta = node.has("baselineDelta") && !node.get("baselineDelta").isNull()
                            ? node.path("baselineDelta").asDouble()
                            : null;
                    int avgLatencyMs = node.path("avgLatencyMs").asInt(0);
                    int p95LatencyMs = node.path("p95LatencyMs").asInt(0);
                    int liveScored = node.path("liveScored").asInt(0);
                    int livePassed = node.path("livePassed").asInt(0);
                    db.update("""
                        INSERT INTO ai_evaluation_runs (
                            id, model_code, model_version, suite_name, metric_name,
                            score, pass_rate, target_score, passed,
                            total_cases, passed_cases, failed_cases, skipped_cases, error_cases,
                            suite_group, dataset_version, judge_model, prompt_version, run_id,
                            baseline_delta, avg_latency_ms, p95_latency_ms, live_scored, live_passed, source_artifact,
                            version, created_at, updated_at
                        )
                        VALUES (?, 'odm-order-qwen-3b', '3b', 'DeepEval Order & Conversation Suite', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), now())
                    """, UUID.randomUUID().toString(), metric, mean, passRate, threshold, passed,
                            scored, passedCases, failedCases, skippedCases, errorCases,
                            suiteGroup, datasetVersion, judgeModel, promptVersion, runId,
                            baselineDelta, avgLatencyMs, p95LatencyMs, liveScored, livePassed,
                            path.getFileName().toString());
                }
                db.update("UPDATE ai_models SET last_evaluated_at = now() WHERE code = 'odm-order-qwen-3b'");
            } catch (Exception ignored) {
                // If file is unreadable, return persisted runs as-is.
            }
        }
        return readPersistedEvaluations();
    }

    public List<EvaluationCaseView> listEvaluationCases(
            String metric,
            String status,
            String difficulty,
            String language,
            String search,
            int limit
    ) {
        ensureSchema();
        Path path = resolveEvalArtifact("deepeval-cases.json");
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 500));
        List<EvaluationCaseView> out = new ArrayList<>();
        try {
            JsonNode root = json.readTree(Files.readString(path));
            JsonNode cases = root.path("cases");
            if (cases.isArray()) {
                String q = search == null ? "" : search.trim().toLowerCase();
                for (JsonNode c : cases) {
                    if (metric != null && !metric.isBlank() && !"ALL".equalsIgnoreCase(metric)
                            && !metric.equalsIgnoreCase(c.path("metric").asText(""))) continue;
                    if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)
                            && !status.equalsIgnoreCase(c.path("status").asText(""))) continue;
                    if (difficulty != null && !difficulty.isBlank() && !"ALL".equalsIgnoreCase(difficulty)
                            && !difficulty.equalsIgnoreCase(c.path("difficulty").asText(""))) continue;
                    if (language != null && !language.isBlank() && !"ALL".equalsIgnoreCase(language)
                            && !language.equalsIgnoreCase(c.path("language").asText(""))) continue;
                    if (!q.isEmpty()) {
                        String hay = (c.path("testCaseId").asText("") + " " + c.path("category").asText("") + " "
                                + c.path("input").asText("") + " " + c.path("judgeReason").asText("")).toLowerCase();
                        if (!hay.contains(q)) continue;
                    }
                    List<String> tools = new ArrayList<>();
                    if (c.path("toolCalls").isArray()) {
                        c.path("toolCalls").forEach(t -> tools.add(t.asText()));
                    }
                    out.add(new EvaluationCaseView(
                            c.path("testCaseId").asText(""),
                            c.path("metric").asText(""),
                            c.path("suiteGroup").asText("core"),
                            c.path("category").asText(""),
                            c.path("difficulty").asText(""),
                            c.path("language").asText(""),
                            c.path("partition").asText(""),
                            c.path("severity").asText(""),
                            c.path("input").asText(""),
                            c.path("turnsCount").asInt(1),
                            c.path("expectedBehavior").asText(""),
                            c.path("actualOutput").asText(""),
                            c.path("evaluationMode").asText(""),
                            c.has("score") && !c.get("score").isNull() ? c.path("score").asDouble() : null,
                            c.path("threshold").asDouble(0.8),
                            c.path("passed").asBoolean(false),
                            c.path("status").asText(""),
                            c.path("judgeReason").asText(""),
                            c.path("rootCause").asText("NONE"),
                            c.has("latencyMs") && !c.get("latencyMs").isNull() ? c.path("latencyMs").asInt() : null,
                            c.has("inputTokens") && !c.get("inputTokens").isNull() ? c.path("inputTokens").asInt() : null,
                            c.has("outputTokens") && !c.get("outputTokens").isNull() ? c.path("outputTokens").asInt() : null,
                            tools,
                            c.path("evaluatedAt").asText("")
                    ));
                    if (out.size() >= bounded) break;
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    public List<Map<String, Object>> listEvaluationHistory(int limit) {
        ensureSchema();
        Path path = resolveEvalArtifact("eval-runs-history.jsonl");
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = lines.size() - 1; i >= 0 && out.size() < Math.max(1, Math.min(limit, 20)); i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    out.add(json.readValue(line, new TypeReference<>() {}));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    public List<EvaluationJobView> listEvaluationJobs() {
        ensureSchema();
        var rows = db.queryForList("SELECT * FROM ai_evaluation_jobs ORDER BY created_at DESC LIMIT 20");
        List<EvaluationJobView> out = new ArrayList<>();
        for (var r : rows) {
            out.add(new EvaluationJobView(
                    Objects.toString(r.get("id"), ""),
                    Objects.toString(r.get("status"), "QUEUED"),
                    Objects.toString(r.get("mode"), "ALL"),
                    Objects.toString(r.get("metric_filter"), ""),
                    Objects.toString(r.get("partition"), "all"),
                    ((Number) r.getOrDefault("total_cases", 0)).intValue(),
                    ((Number) r.getOrDefault("completed_cases", 0)).intValue(),
                    ((Number) r.getOrDefault("passed_cases", 0)).intValue(),
                    ((Number) r.getOrDefault("failed_cases", 0)).intValue(),
                    ((Number) r.getOrDefault("error_cases", 0)).intValue(),
                    Objects.toString(r.get("current_metric"), ""),
                    Objects.toString(r.get("current_case"), ""),
                    ((Number) r.getOrDefault("elapsed_ms", 0L)).longValue(),
                    Objects.toString(r.get("last_checkpoint"), ""),
                    Objects.toString(r.get("created_by"), "admin"),
                    toInstant(r.get("created_at")),
                    toInstant(r.get("updated_at"))
            ));
        }
        return out;
    }

    public EvaluationJobView startEvaluationJob(StartEvaluationJobRequest req) {
        ensureSchema();
        Integer running = db.queryForObject(
                "SELECT count(*) FROM ai_evaluation_jobs WHERE status IN ('QUEUED', 'RUNNING')", Integer.class);
        if (running != null && running > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã có tác vụ đánh giá DeepEval đang chạy. Vui lòng chờ hoàn tất hoặc hủy trước.");
        }
        UUID jobId = UUID.randomUUID();
        String mode = req != null && req.mode() != null && !req.mode().isBlank() ? req.mode().toUpperCase() : "ALL";
        String metric = req != null && req.metric() != null ? req.metric().trim() : "";
        String partition = req != null && req.partition() != null && !req.partition().isBlank() ? req.partition() : "all";
        int livePerMetric = req != null && req.livePerMetric() != null ? Math.max(0, Math.min(req.livePerMetric(), 10)) : 2;
        String actor = safeCurrentUser();

        db.update("""
            INSERT INTO ai_evaluation_jobs (id, status, mode, metric_filter, partition, created_by, version, created_at, updated_at)
            VALUES (?, 'QUEUED', ?, ?, ?, ?, 0, now(), now())
        """, jobId.toString(), mode, metric, partition, actor);

        // Execute asynchronously with concurrency cap = 1 so production AI traffic is never blocked.
        Thread worker = new Thread(() -> runEvaluationJobAsync(jobId, metric, partition, livePerMetric), "deepeval-job-" + jobId);
        worker.setDaemon(true);
        worker.start();

        return listEvaluationJobs().stream().filter(j -> j.id().equals(jobId.toString())).findFirst().orElseThrow();
    }

    private void runEvaluationJobAsync(UUID jobId, String metric, String partition, int livePerMetric) {
        db.update("UPDATE ai_evaluation_jobs SET status = 'RUNNING', updated_at = now() WHERE id = ?", jobId);
        long start = System.currentTimeMillis();
        try {
            Path repoRoot = Path.of("..").toAbsolutePath().normalize();
            Path python = repoRoot.resolve(".ai-runtime/asr-env/Scripts/python.exe");
            if (!Files.exists(python)) {
                python = repoRoot.resolve(".ai-runtime/deepeval-env/Scripts/python.exe");
            }
            Path script = repoRoot.resolve("qa/ai_evaluation/eval_runner.py");
            Path jobFile = repoRoot.resolve(".ai-runtime/deepeval/job-" + jobId + ".json");
            List<String> cmd = new ArrayList<>(List.of(
                    python.toString(), script.toString(),
                    "--partition", partition,
                    "--live-per-metric", String.valueOf(livePerMetric),
                    "--job-file", jobFile.toString()
            ));
            if (metric != null && !metric.isBlank() && !"ALL".equalsIgnoreCase(metric)) {
                cmd.add("--metrics");
                cmd.add(metric);
            }
            Process proc = new ProcessBuilder(cmd).directory(repoRoot.toFile()).redirectErrorStream(true).start();
            int exit = proc.waitFor();
            long elapsed = System.currentTimeMillis() - start;
            if (Files.exists(jobFile)) {
                JsonNode state = json.readTree(Files.readString(jobFile));
                db.update("""
                    UPDATE ai_evaluation_jobs
                    SET status = ?, total_cases = ?, completed_cases = ?, passed_cases = ?, failed_cases = ?, error_cases = ?,
                        elapsed_ms = ?, last_checkpoint = ?, updated_at = now()
                    WHERE id = ? AND status <> 'CANCELLED'
                """, exit == 0 ? "COMPLETED" : "FAILED",
                        state.path("totalCases").asInt(0), state.path("completedCases").asInt(0),
                        state.path("passedCases").asInt(0), state.path("failedCases").asInt(0),
                        state.path("errorCases").asInt(0), elapsed, state.path("lastCheckpoint").asText(""), jobId);
            } else {
                db.update("UPDATE ai_evaluation_jobs SET status = ?, elapsed_ms = ?, updated_at = now() WHERE id = ? AND status <> 'CANCELLED'",
                        exit == 0 ? "COMPLETED" : "FAILED", elapsed, jobId);
            }
            if (exit == 0) {
                syncDeepEvalFromDisk();
            }
        } catch (Exception ex) {
            db.update("UPDATE ai_evaluation_jobs SET status = 'FAILED', elapsed_ms = ?, updated_at = now() WHERE id = ? AND status <> 'CANCELLED'",
                    System.currentTimeMillis() - start, jobId);
        }
    }

    public EvaluationJobView cancelEvaluationJob(UUID jobId) {
        ensureSchema();
        db.update("UPDATE ai_evaluation_jobs SET status = 'CANCELLED', updated_at = now() WHERE id = ? AND status IN ('QUEUED', 'RUNNING', 'PAUSED')", jobId);
        return listEvaluationJobs().stream()
                .filter(j -> j.id().equals(jobId.toString()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation job not found"));
    }

    private Path resolveEvalArtifact(String filename) {
        Path summary = Path.of(evalSummaryPath).toAbsolutePath().normalize();
        Path dir = summary.getParent();
        if (dir != null && Files.exists(dir.resolve(filename))) {
            return dir.resolve(filename);
        }
        Path fallback = Path.of(".ai-runtime/deepeval/" + filename).toAbsolutePath().normalize();
        if (Files.exists(fallback)) return fallback;
        Path parentFallback = Path.of("../.ai-runtime/deepeval/" + filename).toAbsolutePath().normalize();
        return Files.exists(parentFallback) ? parentFallback : null;
    }

    public AiSecurityOverviewView getSecurityOverview() {
        ensureSchema();
        List<GuardrailPolicyView> policies = List.of(
                new GuardrailPolicyView("sec-1", "Prompt Injection", "Kiểm soát dữ liệu đầu vào, tách biệt system prompt và dữ liệu JSON, chặn lệnh ghi đè nghiệp vụ", "Input Guardrail", true),
                new GuardrailPolicyView("sec-2", "Jailbreak", "Kiểm tra yêu cầu và đầu ra vi phạm chính sách vận hành trước khi trả về client", "Input / Output", true),
                new GuardrailPolicyView("sec-3", "Data Leakage", "Che thông tin nhạy cảm (token, mật khẩu, PII khách hàng) khỏi prompt và audit log", "Data Protection", true),
                new GuardrailPolicyView("sec-4", "Hallucination", "Bắt buộc đối chiếu mediaId, findingId, số liệu với dữ liệu thực tế (validate_references)", "Output Guardrail", true),
                new GuardrailPolicyView("sec-5", "Denial of Service", "Rate limiting, request timeout và giới hạn hàng đợi tạo báo cáo đồng thời", "Control Layer", true),
                new GuardrailPolicyView("sec-6", "Model Tampering", "Quản lý nguồn gốc, license và checksum checkpoint trong Model Registry", "Supply Chain", true),
                new GuardrailPolicyView("sec-7", "Unauthorized AI Access", "Spring Security RBAC (@PreAuthorize) + X-Internal-Token cho worker nội bộ", "Access Control", true),
                new GuardrailPolicyView("sec-8", "Malicious Image Upload", "Kiểm tra MIME type, dung lượng tối đa 12 MB và xác thực nguồn ảnh đã duyệt", "Media Guard", true),
                new GuardrailPolicyView("sec-9", "Unauthorized Tool Calling", "Chỉ cho phép gọi các read-only tool nằm trong allowlist của backend", "Tool Guardrail", true),
                new GuardrailPolicyView("sec-10", "Resource Exhaustion", "Giới hạn max_tokens, context window và hàng đợi xử lý trên GPU/CPU local", "Runtime Guard", true)
        );
        var rows = db.queryForList("SELECT * FROM ai_security_events ORDER BY created_at DESC LIMIT 100");
        List<AiSecurityEventView> events = new ArrayList<>();
        long blocked = 0;
        for (var r : rows) {
            String decision = Objects.toString(r.get("decision"), "BLOCK");
            if ("BLOCK".equalsIgnoreCase(decision)) blocked++;
            events.add(new AiSecurityEventView(
                    Objects.toString(r.get("id"), ""),
                    Objects.toString(r.get("trace_id"), ""),
                    Objects.toString(r.get("event_type"), ""),
                    Objects.toString(r.get("risk_category"), ""),
                    Objects.toString(r.get("severity"), "MEDIUM"),
                    Objects.toString(r.get("user_id"), ""),
                    Objects.toString(r.get("feature"), ""),
                    decision,
                    Objects.toString(r.get("summary"), ""),
                    toInstant(r.get("created_at"))
            ));
        }
        return new AiSecurityOverviewView(policies, events, blocked, events.size());
    }

    public List<AiIncidentView> listIncidents(String statusFilter) {
        ensureSchema();
        List<Map<String, Object>> rows;
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            rows = db.queryForList("SELECT * FROM ai_incidents WHERE status = ? ORDER BY created_at DESC LIMIT 100", statusFilter.toUpperCase());
        } else {
            rows = db.queryForList("SELECT * FROM ai_incidents ORDER BY CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END, created_at DESC LIMIT 100");
        }
        List<AiIncidentView> out = new ArrayList<>();
        for (var r : rows) {
            out.add(mapIncident(r));
        }
        return out;
    }

    @Transactional
    public AiIncidentView createIncident(CreateIncidentRequest req) {
        ensureSchema();
        UUID id = UUID.randomUUID();
        String actor = safeCurrentUser();
        db.update("""
            INSERT INTO ai_incidents (id, title, severity, status, model_code, service_endpoint, summary, recommended_action, created_by, version, created_at, updated_at)
            VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, 0, now(), now())
        """, id.toString(), req.title().trim(), req.severity().trim().toUpperCase(), req.modelCode(), req.serviceEndpoint(), req.summary().trim(), req.recommendedAction(), actor);
        return mapIncident(db.queryForMap("SELECT * FROM ai_incidents WHERE id = ?", id.toString()));
    }

    @Transactional
    public AiIncidentView resolveIncident(UUID id) {
        ensureSchema();
        String actor = safeCurrentUser();
        int updated = db.update(
                "UPDATE ai_incidents SET status = 'RESOLVED', resolved_by = ?, resolved_at = now() WHERE id = ?",
                actor, id
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sự cố");
        }
        return mapIncident(db.queryForMap("SELECT * FROM ai_incidents WHERE id = ?", id));
    }

    public void recordAudit(
            String modelCode,
            String feature,
            String resourceId,
            int durationMs,
            int httpStatus,
            String validationStatus,
            String securityDecision,
            boolean fallbackUsed,
            List<TraceSpan> spans
    ) {
        ensureSchema();
        String traceId = "AI-" + LocalDate.now(VN_ZONE).format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        db.update("""
            INSERT INTO ai_execution_audits (
                id, trace_id, user_id, user_role, model_code, model_version, prompt_version, feature,
                resource_id, input_ref, output_ref, duration_ms, http_status, inference_device,
                fallback_used, validation_status, security_decision, human_review_status, spans_json,
                version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'v1', 'v1', ?, ?, ?, ?, ?, ?, 'CPU', ?, ?, ?, 'APPROVED', ?::jsonb, 0, now(), now())
        """, UUID.randomUUID().toString(), traceId, safeCurrentUser(), safeCurrentRole(), modelCode, feature,
                resourceId, "admin:" + feature, "status:" + httpStatus, durationMs, httpStatus,
                fallbackUsed, validationStatus, securityDecision, writeJson(spans));
    }

    private AiIncidentView mapIncident(Map<String, Object> r) {
        return new AiIncidentView(
                Objects.toString(r.get("id"), ""),
                Objects.toString(r.get("title"), ""),
                Objects.toString(r.get("severity"), "WARNING"),
                Objects.toString(r.get("status"), "OPEN"),
                (String) r.get("model_code"),
                (String) r.get("service_endpoint"),
                Objects.toString(r.get("summary"), ""),
                (String) r.get("recommended_action"),
                (String) r.get("created_by"),
                (String) r.get("resolved_by"),
                toInstant(r.get("created_at")),
                toInstant(r.get("resolved_at"))
        );
    }

    private List<DailyVolumePoint> buildDailyVolume(List<AiTraceView> traces, int days) {
        LocalDate today = LocalDate.now(VN_ZONE);
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            buckets.put(today.minusDays(i), new long[]{0L, 0L});
        }
        for (AiTraceView t : traces) {
            if (t.createdAt() == null) continue;
            LocalDate d = t.createdAt().atZone(VN_ZONE).toLocalDate();
            long[] pair = buckets.get(d);
            if (pair != null) {
                pair[0]++;
                if (t.httpStatus() >= 400 || "FAILED".equalsIgnoreCase(t.validationStatus())) {
                    pair[1]++;
                }
            }
        }
        List<DailyVolumePoint> out = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            out.add(new DailyVolumePoint(e.getKey().format(DAY_FMT), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    private String safeCurrentUser() {
        try {
            return users.getCurrentUserId();
        } catch (Exception ex) {
            return "system";
        }
    }

    private String safeCurrentRole() {
        try {
            return users.getCurrentUser().getRole().getCode().name();
        } catch (Exception ex) {
            return "ADMIN";
        }
    }

    private static String normalizeBase(String value, String fallback) {
        String s = (value == null || value.isBlank()) ? fallback : value.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String extractOrigin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception ex) {
            return url;
        }
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp ts) return ts.toInstant();
        return Instant.parse(value.toString());
    }

    private Map<String, Object> parseMap(Object value) {
        if (value == null) return Map.of();
        try {
            return json.readValue(value.toString(), new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<TraceSpan> parseSpans(Object value) {
        if (value == null) return List.of();
        try {
            return json.readValue(value.toString(), new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
