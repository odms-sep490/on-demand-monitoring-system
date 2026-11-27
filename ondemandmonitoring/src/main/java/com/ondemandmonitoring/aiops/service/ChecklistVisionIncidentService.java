package com.ondemandmonitoring.aiops.service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, body-free operational evidence for the existing admin incident workflow. */
@Service
public class ChecklistVisionIncidentService {
    private static final Logger LOG = LoggerFactory.getLogger(ChecklistVisionIncidentService.class);
    private static final String MODEL = "checklist-image-vision";
    private static final String ACTOR = "SYSTEM:CHECKLIST_VISION";
    private static final String ACTION = "Kiểm tra kết nối nhà cung cấp, thời gian chờ và định dạng phản hồi; xem mã truy vết liên quan.";
    private static final Set<String> FAILURES = Set.of("AI_PROVIDER_UNAVAILABLE", "AI_RESPONSE_INVALID");
    private final JdbcTemplate db;

    public ChecklistVisionIncidentService(JdbcTemplate db) {
        this.db = db;
    }

    /** Invoke only for attempted provider inference, with a canonical code (never response text). */
    public void recordFailure(String traceId, int status, String errorCode) {
        if (!validTrace(traceId) || !FAILURES.contains(errorCode == null ? "" : errorCode)) return;
        String summary = "Lỗi nhà cung cấp phân tích ảnh checklist. Mã lỗi: " + errorCode
                + "; trạng thái HTTP: " + status + ". Mã truy vết: " + traceId;
        persist(() -> {
            int updated = db.update("""
                    UPDATE ai_incidents SET summary = ?, recommended_action = ?,
                        version = version + 1, updated_at = now()
                    WHERE model_code = ? AND created_by = ? AND status = 'OPEN'
                    """, summary, ACTION, MODEL, ACTOR);
            if (updated == 0) db.update("""
                    INSERT INTO ai_incidents (id, title, severity, status, model_code, summary,
                        recommended_action, created_by, version, created_at, updated_at)
                    VALUES (?, 'Lỗi nhà cung cấp phân tích ảnh checklist', 'WARNING', 'OPEN', ?, ?, ?, ?, 0, now(), now())
                    """, UUID.randomUUID().toString(), MODEL, summary, ACTION, ACTOR);
        });
    }

    /** A successful real inference is recovery evidence; an administrator still owns resolution. */
    public void recovered(String traceId) {
        if (!validTrace(traceId)) return;
        persist(() -> db.update("""
                UPDATE ai_incidents SET recommended_action = ?, version = version + 1, updated_at = now()
                WHERE model_code = ? AND created_by = ? AND status = 'OPEN'
                """, "Phân tích ảnh đã hoạt động trở lại. Mã truy vết phục hồi: " + traceId
                + ". Kiểm tra hoạt động ổn định và đóng sự cố sau khi xác minh.", MODEL, ACTOR));
    }

    private void persist(Runnable operation) {
        try {
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(
                    Objects.requireNonNull(db.getDataSource())));
            // Independent storage must not poison the assessment/trace transaction.
            transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transactions.setTimeout(5);
            transactions.executeWithoutResult(tx -> {
                // Shared across JVMs; serializes recovery and failure without schema additions.
                db.execute("SET LOCAL lock_timeout = '2s'");
                db.execute("SET LOCAL statement_timeout = '4s'");
                db.execute("SELECT pg_advisory_xact_lock(1847463021, 1129270867)");
                operation.run();
            });
        } catch (RuntimeException e) {
            // Never log SQL exceptions: drivers may include parameter values or provider text.
            LOG.warn("Could not persist checklist vision incident evidence");
        }
    }

    private static boolean validTrace(String traceId) {
        return traceId != null && traceId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}");
    }
}
