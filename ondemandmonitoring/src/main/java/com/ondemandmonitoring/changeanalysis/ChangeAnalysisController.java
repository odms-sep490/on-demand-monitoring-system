package com.ondemandmonitoring.changeanalysis;

import com.ondemandmonitoring.media.service.ICustomerMediaService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/customer/ai/change-analyses")
public class ChangeAnalysisController {
    private static final int LIMIT = 8 * 1024 * 1024;
    private static final String FIELDS = "id,status,error_code,created_at,started_at,completed_at,before_asset,after_asset";
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ICustomerMediaService media;
    private final String worker;
    private final String token;
    private final java.util.concurrent.ExecutorService inferenceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public ChangeAnalysisController(JdbcTemplate db, TransactionTemplate transactions, ICustomerMediaService media,
            @Value("${change-analysis.worker-url:}") String worker,
            @Value("${change-analysis.token:}") String token) {
        this.db = db;
        this.transactions = transactions;
        this.media = media;
        this.worker = worker;
        this.token = token;
    }

    @Bean
    ApplicationRunner changeAnalysisSchema() {
        return args -> db.execute("""
            CREATE TABLE IF NOT EXISTS ai_change_analysis (
                id uuid PRIMARY KEY, owner text NOT NULL, request_key uuid NOT NULL,
                input_hash text NOT NULL, before_asset text, after_asset text,
                before_image bytea, after_image bytea, result text,
                status text NOT NULL, error_code text,
                created_at timestamptz NOT NULL DEFAULT now(),
                started_at timestamptz, completed_at timestamptz,
                UNIQUE (owner, request_key)
            )
            """);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object create(Authentication auth, @RequestParam UUID requestKey,
            @RequestParam(required = false) MultipartFile before, @RequestParam(required = false) MultipartFile after,
            @RequestParam(required = false) String beforeAsset, @RequestParam(required = false) String afterAsset)
            throws Exception {
        if (worker.isBlank() || token.isBlank()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED");
        try (var readiness = fetch(HttpRequest.newBuilder(URI.create(worker + "/ready"))
                .timeout(Duration.ofSeconds(8)).GET().build(), 4096)) {
            // Reject before queuing image data when the inference model is unavailable.
        } catch (ResponseStatusException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, exception.getReason());
        } catch (Exception exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_UNAVAILABLE");
        }
        var left = input(before, beforeAsset);
        var right = input(after, afterAsset);
        var digest = MessageDigest.getInstance("SHA-256");
        var checksum = HexFormat.of().formatHex(digest.digest(left)) + HexFormat.of().formatHex(digest.digest(right));
        String owner = auth.getName();
        UUID id = transactions.execute(tx -> {
            db.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, owner);
            var existing = db.queryForList("SELECT id,input_hash FROM ai_change_analysis WHERE owner=? AND request_key=?",
                    owner, requestKey);
            if (!existing.isEmpty()) {
                if (!checksum.equals(existing.getFirst().get("input_hash"))) {
                    throw error(HttpStatus.CONFLICT, "REQUEST_KEY_CONFLICT");
                }
                return (UUID) existing.getFirst().get("id");
            }
            db.query("SELECT pg_advisory_xact_lock(701417277::bigint)", rs -> null);
            int queued = db.queryForObject("SELECT count(*) FROM ai_change_analysis WHERE status IN ('PENDING','PROCESSING')",
                    Integer.class);
            if (queued >= 30) throw error(HttpStatus.TOO_MANY_REQUESTS, "JOB_LIMIT");
            int recent = db.queryForObject("""
                SELECT count(*) FROM ai_change_analysis WHERE owner=? AND
                (status IN ('PENDING','PROCESSING') OR created_at > now() - interval '1 minute')
                """, Integer.class, owner);
            if (recent >= 3) throw error(HttpStatus.TOO_MANY_REQUESTS, "JOB_LIMIT");
            UUID newId = UUID.randomUUID();
            db.update("""
                INSERT INTO ai_change_analysis
                (id,owner,request_key,input_hash,before_asset,after_asset,before_image,after_image,status)
                VALUES (?,?,?,?,?,?,?,?,'PENDING')
                """, newId, owner, requestKey, checksum, beforeAsset, afterAsset, left, right);
            return newId;
        });
        return get(auth, id);
    }

    private byte[] input(MultipartFile file, String asset) throws Exception {
        if ((file == null) == (asset == null)) throw error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_INPUT");
        byte[] bytes;
        if (file != null) {
            if (file.getSize() > LIMIT) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "UNSUPPORTED_INPUT");
            bytes = file.getBytes();
        } else {
            var link = media.createOriginalDownload(asset);
            var request = HttpRequest.newBuilder(URI.create(link.downloadUrl())).timeout(Duration.ofSeconds(30)).GET();
            try (var stream = fetch(request.build(), LIMIT)) {
                bytes = stream.readAllBytes();
            }
        }
        try (var stream = new MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_INPUT");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (!List.of("jpeg", "png").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT))
                        || reader.getWidth(0) < 256 || reader.getHeight(0) < 256
                        || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096) {
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_INPUT");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_INPUT");
        }
        return bytes;
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public Object list(Authentication auth) {
        return db.queryForList("SELECT " + FIELDS
                + " FROM ai_change_analysis WHERE owner=? ORDER BY created_at DESC LIMIT 50", auth.getName());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Object get(Authentication auth, @PathVariable UUID id) {
        var rows = db.queryForList("SELECT " + FIELDS + " FROM ai_change_analysis WHERE owner=? AND id=?",
                auth.getName(), id);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        return rows.getFirst();
    }

    @GetMapping(value = "/{id}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<String> result(Authentication auth, @PathVariable UUID id) {
        var rows = db.queryForList("SELECT result FROM ai_change_analysis WHERE owner=? AND id=? AND status='COMPLETED'",
                String.class, auth.getName(), id);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows.getFirst());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id) {
        if (db.update("DELETE FROM ai_change_analysis WHERE owner=? AND id=?", auth.getName(), id) == 0) {
            throw error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 15000)
    public void process() {
        if (worker.isBlank() || token.isBlank() || !running.compareAndSet(false, true)) return;
        inferenceExecutor.execute(() -> {
            try {
                processOne();
            } finally {
                running.set(false);
            }
        });
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        inferenceExecutor.shutdownNow();
    }

    private void processOne() {
        db.update("""
            UPDATE ai_change_analysis SET status='FAILED', error_code='WORKER_TIMEOUT',
            before_image=NULL, after_image=NULL, completed_at=now()
            WHERE status='PROCESSING' AND started_at < now() - interval '10 minutes'
            """);
        var jobs = db.queryForList("""
            UPDATE ai_change_analysis SET status='PROCESSING', started_at=now()
            WHERE id=(SELECT id FROM ai_change_analysis WHERE status='PENDING'
                      ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id,before_image,after_image
            """);
        if (jobs.isEmpty()) return;
        var job = jobs.getFirst();
        var id = job.get("id");
        try {
            String boundary = UUID.randomUUID().toString();
            var body = HttpRequest.BodyPublishers.concat(
                    part(boundary, "before"), HttpRequest.BodyPublishers.ofByteArray((byte[]) job.get("before_image")),
                    part(boundary, "after"), HttpRequest.BodyPublishers.ofByteArray((byte[]) job.get("after_image")),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var request = HttpRequest.newBuilder(URI.create(worker + "/infer")).timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
            try (var stream = fetch(request, 150 * 1024 * 1024)) {
                String result = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                db.update("""
                    UPDATE ai_change_analysis SET status='COMPLETED',result=?,completed_at=now(),
                    before_image=NULL,after_image=NULL WHERE id=? AND status='PROCESSING'
                    """, result, id);
            }
        } catch (Exception exception) {
            String code = exception instanceof ResponseStatusException status ? status.getReason() : "WORKER_UNAVAILABLE";
            LoggerFactory.getLogger(getClass()).warn("Change analysis {} failed: {}", id, code);
            db.update("""
                UPDATE ai_change_analysis SET status='FAILED',error_code=?,completed_at=now(),
                before_image=NULL,after_image=NULL WHERE id=? AND status='PROCESSING'
                """, code, id);
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void expire() {
        db.update("DELETE FROM ai_change_analysis WHERE created_at < now() - interval '30 days'");
    }

    private InputStream fetch(HttpRequest request, int limit) throws Exception {
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(
                request.timeout().orElse(Duration.ofSeconds(30)).toSeconds(),
                java.util.concurrent.TimeUnit.SECONDS);
        try (var body = response.body(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> body.readNBytes((response.statusCode() == 200 ? limit : 4096) + 1));
            byte[] bytes;
            try {
                bytes = future.get(request.timeout().orElse(Duration.ofSeconds(30)).toSeconds(),
                        java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception exception) {
                body.close();
                future.cancel(true);
                throw exception;
            }
            if (response.statusCode() != 200) {
                String detail = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                String code = List.of("ALIGNMENT_FAILED", "UNSUPPORTED_INPUT", "NOT_CONFIGURED",
                        "WORKER_BUSY", "INFERENCE_FAILED").stream().filter(detail::contains)
                        .findFirst().orElse("WORKER_UNAVAILABLE");
                throw error(HttpStatus.BAD_GATEWAY, code);
            }
            if (bytes.length > limit) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SIZE_LIMIT");
            return new java.io.ByteArrayInputStream(bytes);
        }
    }

    private static HttpRequest.BodyPublisher part(String boundary, String name) {
        return HttpRequest.BodyPublishers.ofString("\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"image\"\r\nContent-Type: application/octet-stream\r\n\r\n");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<java.util.Map<String, String>> handleAnalysisError(ResponseStatusException exception) {
        String code = exception.getReason() == null ? "ANALYSIS_FAILED" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(java.util.Map.of("code", code, "message", code));
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
