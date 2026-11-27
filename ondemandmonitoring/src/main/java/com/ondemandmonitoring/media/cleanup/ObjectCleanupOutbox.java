package com.ondemandmonitoring.media.cleanup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class ObjectCleanupOutbox {
    private final JdbcTemplate db;
    private final TransactionTemplate isolated;
    private final ObjectCleanupProperties properties;

    public ObjectCleanupOutbox(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            ObjectCleanupProperties properties) {
        this.db = db;
        this.properties = properties;
        this.isolated = new TransactionTemplate(manager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Must share the owner's transaction. Only immutable, never-reused object keys are supported.
     */
    public String enqueue(String bucket, String key, String guardType, String ownerId) {
        requireTransaction();
        return insert(bucket, key, guardType, ownerId, 0, true);
    }

    /**
     * Persist BEFORE uploading. Survives outer rollback and process termination; never locks the
     * owner.
     */
    public String reserveUpload(String bucket, String key, String guardType, String ownerId) {
        return isolated.execute(
                tx ->
                        insert(
                                bucket,
                                key,
                                guardType,
                                ownerId,
                                properties.getUploadGraceSeconds(),
                                false));
    }

    /**
     * Cancels only if the reference update commits. A later enqueue can reactivate this
     * reservation.
     */
    public void cancelReservation(String id) {
        requireTransaction();
        db.update(
                """
UPDATE object_cleanup_outbox SET status='CANCELLED', lease_token=NULL, lease_until=NULL,
  completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
WHERE id=? AND status IN ('PENDING','PROCESSING')
""",
                id);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Object cleanup must share an active business transaction");
    }

    private String insert(
            String bucket,
            String key,
            String guardType,
            String ownerId,
            int delay,
            boolean reactivate) {
        if (bucket == null
                || bucket.isBlank()
                || bucket.length() > 255
                || key == null
                || key.isBlank()
                || key.getBytes(StandardCharsets.UTF_8).length > 1024
                || guardType == null
                || !guardType.matches("[A-Z_]{1,64}")
                || ownerId == null
                || ownerId.isBlank()
                || ownerId.length() > 255)
            throw new IllegalArgumentException("Invalid object cleanup identity");
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (bucket + "\0" + key + "\0" + guardType + "\0"
                                                                    + ownerId)
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String id = UUID.randomUUID().toString();
        db.update(
                """
INSERT INTO object_cleanup_outbox(id,object_hash,bucket,object_key,guard_type,owner_id,status,attempts,
  next_attempt_at,created_at,updated_at)
VALUES (?,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (object_hash) DO NOTHING
""",
                id,
                hash,
                bucket,
                key,
                guardType,
                ownerId,
                delay);
        var row =
                db.queryForMap(
                        "SELECT id,bucket,object_key,guard_type,owner_id FROM object_cleanup_outbox"
                                + " WHERE object_hash=? FOR UPDATE",
                        hash);
        if (!bucket.equals(row.get("bucket"))
                || !key.equals(row.get("object_key"))
                || !guardType.equals(row.get("guard_type"))
                || !ownerId.equals(row.get("owner_id")))
            throw new IllegalStateException("Object cleanup identity collision");
        id = row.get("id").toString();
        // Reconciliation must not reset a pending retry deadline or erase its last failure.
        if (reactivate)
            db.update(
                    """
                    UPDATE object_cleanup_outbox SET
                      attempts=CASE WHEN status='CANCELLED' THEN 0 ELSE attempts END,
                      status='PENDING', next_attempt_at=CURRENT_TIMESTAMP, completed_at=NULL,
                      last_error=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND status='CANCELLED'
                    """,
                    id);
        return id;
    }

    /** Lock a provisional task in the owner transaction before storage I/O. */
    public void protectReservation(String id) {
        requireTransaction();
        var states =
                db.queryForList(
                        "SELECT status FROM object_cleanup_outbox WHERE id=? FOR UPDATE",
                        String.class,
                        id);
        if (states.size() != 1 || !"PENDING".equals(states.getFirst())) {
            throw new IllegalStateException("Upload reservation is no longer pending");
        }
    }

    public String status(String id) {
        var states =
                db.queryForList(
                        "SELECT status FROM object_cleanup_outbox WHERE id=?", String.class, id);
        return states.isEmpty() ? null : states.getFirst();
    }

    public Optional<Claim> claimNext() {
        return isolated.execute(
                tx -> {
                    // Crashed final attempts must become inspectable, not remain stuck PROCESSING
                    // forever.
                    db.update(
                            """
WITH exhausted AS (
  SELECT id FROM object_cleanup_outbox WHERE attempts >= ? AND
    (status='PENDING' OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP))
  ORDER BY updated_at LIMIT 1000 FOR UPDATE SKIP LOCKED
)
UPDATE object_cleanup_outbox t SET status='DEAD',last_error='LEASE_EXHAUSTED',lease_token=NULL,
  lease_until=NULL,updated_at=CURRENT_TIMESTAMP FROM exhausted e WHERE t.id=e.id
""",
                            properties.getMaxAttempts());
                    String token = UUID.randomUUID().toString();
                    var rows =
                            db.query(
                                    """
WITH candidate AS (
  SELECT id FROM object_cleanup_outbox
  WHERE attempts < ? AND ((status='PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
    OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP))
  ORDER BY next_attempt_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
)
UPDATE object_cleanup_outbox t SET status='PROCESSING',attempts=t.attempts+1,
  lease_token=?,lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),updated_at=CURRENT_TIMESTAMP
FROM candidate c WHERE t.id=c.id
RETURNING t.id,t.bucket,t.object_key,t.guard_type,t.owner_id,t.attempts,t.lease_token
""",
                                    (rs, n) ->
                                            new Claim(
                                                    rs.getString("id"),
                                                    rs.getString("lease_token"),
                                                    rs.getString("bucket"),
                                                    rs.getString("object_key"),
                                                    rs.getString("guard_type"),
                                                    rs.getString("owner_id"),
                                                    rs.getInt("attempts")),
                                    properties.getMaxAttempts(),
                                    token,
                                    properties.getLeaseSeconds());
                    return rows.stream().findFirst();
                });
    }

    public ObjectCleanupGuard.Decision inspect(Claim claim, ObjectCleanupGuard guard) {
        return isolated.execute(tx -> guard.inspect(claim.bucket(), claim.key(), claim.ownerId()));
    }

    public void complete(Claim claim) {
        finish(claim, "COMPLETED");
    }

    public void cancel(Claim claim) {
        finish(claim, "CANCELLED");
    }

    private void finish(Claim claim, String status) {
        isolated.executeWithoutResult(
                tx ->
                        db.update(
                                """
UPDATE object_cleanup_outbox SET status=?,completed_at=CURRENT_TIMESTAMP,lease_token=NULL,
  lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP
WHERE id=? AND status='PROCESSING' AND lease_token=?
""",
                                status,
                                claim.id(),
                                claim.token()));
    }

    public void defer(Claim claim) {
        isolated.executeWithoutResult(
                tx ->
                        db.update(
                                """
UPDATE object_cleanup_outbox SET status='PENDING',attempts=GREATEST(0,attempts-1),
  next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),lease_token=NULL,
  lease_until=NULL,updated_at=CURRENT_TIMESTAMP,last_error='OWNER_BUSY'
WHERE id=? AND status='PROCESSING' AND lease_token=?
""",
                                properties.getRetryBaseSeconds(),
                                claim.id(),
                                claim.token()));
    }

    public void fail(Claim claim, RuntimeException failure) {
        long delay =
                Math.min(
                        properties.getRetryMaxSeconds(),
                        (long) properties.getRetryBaseSeconds()
                                * (1L << Math.min(30, Math.max(0, claim.attempts() - 1))));
        isolated.executeWithoutResult(
                tx ->
                        db.update(
                                """
UPDATE object_cleanup_outbox SET status=?,last_error=?,next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
  lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
WHERE id=? AND status='PROCESSING' AND lease_token=?
""",
                                claim.attempts() >= properties.getMaxAttempts()
                                        ? "DEAD"
                                        : "PENDING",
                                failure.getClass()
                                        .getSimpleName()
                                        .substring(
                                                0,
                                                Math.min(
                                                        100,
                                                        failure.getClass()
                                                                .getSimpleName()
                                                                .length())),
                                delay,
                                claim.id(),
                                claim.token()));
    }

    public boolean retry(String id) {
        return Boolean.TRUE.equals(
                isolated.execute(
                        tx ->
                                db.update(
                                                """
UPDATE object_cleanup_outbox SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,
  last_error=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
WHERE id=? AND status='DEAD'
""",
                                                id)
                                        == 1));
    }

    public int purgeCompleted() {
        return isolated.execute(
                tx ->
                        db.update(
                                """
DELETE FROM object_cleanup_outbox WHERE id IN (
  SELECT id FROM object_cleanup_outbox WHERE status IN ('COMPLETED','CANCELLED')
    AND completed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
  ORDER BY completed_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
""",
                                properties.getRetentionDays()));
    }

    public TaskPage list(String status, int page, int size) {
        if (!Set.of("PENDING", "PROCESSING", "COMPLETED", "CANCELLED", "DEAD").contains(status)
                || page < 0
                || page > 100000
                || size < 1
                || size > 100) throw new IllegalArgumentException("Invalid cleanup filter");
        Long total =
                db.queryForObject(
                        "SELECT count(*) FROM object_cleanup_outbox WHERE status=?",
                        Long.class,
                        status);
        var tasks =
                db.query(
                        """
SELECT id,bucket,object_key,guard_type,owner_id,status,attempts,last_error,created_at,updated_at
FROM object_cleanup_outbox WHERE status=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
""",
                        (rs, n) -> taskView(rs),
                        status,
                        size,
                        (long) page * size);
        return new TaskPage(total == null ? 0 : total, tasks);
    }

    private static TaskView taskView(ResultSet r) throws SQLException {
        return new TaskView(
                r.getString("id"),
                r.getString("bucket"),
                r.getString("object_key"),
                r.getString("guard_type"),
                r.getString("owner_id"),
                r.getString("status"),
                r.getInt("attempts"),
                r.getString("last_error"),
                r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("updated_at").toInstant());
    }

    public record Claim(
            String id,
            String token,
            String bucket,
            String key,
            String guardType,
            String ownerId,
            int attempts) {}

    public record TaskView(
            String id,
            String bucket,
            String key,
            String guardType,
            String ownerId,
            String status,
            int attempts,
            String lastError,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    public record TaskPage(long total, List<TaskView> items) {}
}
