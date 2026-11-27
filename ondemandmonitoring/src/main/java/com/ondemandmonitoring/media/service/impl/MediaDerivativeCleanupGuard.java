package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.cleanup.ObjectCleanupGuard;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Protects published references and fences stale attempts in the worker's short transaction. */
@Component
@RequiredArgsConstructor
public class MediaDerivativeCleanupGuard implements ObjectCleanupGuard {
    public static final String TYPE = "MEDIA_DERIVATIVE";
    private final JdbcTemplate db;

    @Value("${app.media.derivatives.stale-minutes:15}")
    private int staleMinutes = 15;

    @Override
    public String type() { return TYPE; }

    @Override
    public Decision inspect(String bucket, String key, String ownerId) {
        var owner = db.queryForList("SELECT id FROM drone_media WHERE id=? FOR UPDATE SKIP LOCKED", ownerId);
        if (owner.isEmpty() && Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM drone_media WHERE id=?)", Boolean.class, ownerId)))
            return Decision.DEFER;
        // Protect every original and published derivative, even when referenced by another media.
        if (Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM drone_media m
                  LEFT JOIN media_derivatives d ON d.media_id=m.id
                  WHERE m.s3_bucket=? AND (m.s3_key=? OR d.thumbnail_key=? OR d.preview_key=?))
                """, Boolean.class, bucket, key, key, key))) return Decision.REFERENCED;
        if (owner.isEmpty()) return Decision.DELETE;
        var attempts = db.queryForList("""
                SELECT m.s3_bucket,m.storage_status,m.media_status,d.derivative_status,
                       d.derivative_attempt_count,d.derivative_processed_at
                FROM drone_media m LEFT JOIN media_derivatives d ON d.media_id=m.id WHERE m.id=?
                """, ownerId);
        var attempt = attempts.getFirst();
        String root = "derived/" + ownerId + "/v1/attempt-" + attempt.get("derivative_attempt_count") + "/";
        boolean currentPath = java.util.stream.Stream.of("thumbnail.jpg", "preview.jpg", "preview.mp4")
                .anyMatch(name -> key.equals(root + name) || key.endsWith("/" + root + name));
        if (!Objects.equals(bucket, attempt.get("s3_bucket")) || !currentPath
                || !"AVAILABLE".equals(attempt.get("storage_status"))
                || "REMOVED".equals(attempt.get("media_status"))
                || !"PROCESSING".equals(attempt.get("derivative_status"))) return Decision.DELETE;
        var processed = (java.sql.Timestamp) attempt.get("derivative_processed_at");
        if (processed != null && !processed.toInstant().isBefore(
                Instant.now().minusSeconds(Math.max(1, staleMinutes) * 60L))) return Decision.DEFER;
        // The root lock serializes against persistSuccess. A late worker cannot publish deleted keys.
        db.update("""
                UPDATE media_derivatives SET derivative_status='FAILED',
                  derivative_error_code='DERIVATIVE_ATTEMPT_EXPIRED',version=version+1
                WHERE media_id=? AND derivative_status='PROCESSING'
                """, ownerId);
        return Decision.DELETE;
    }
}
