package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.cleanup.ObjectCleanupGuard;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** Current originals, derivatives and live staging attempts protect shared immutable keys. */
@Component
@RequiredArgsConstructor
public class MediaObjectCleanupGuard implements ObjectCleanupGuard {
    public static final String TYPE = "MEDIA_OBJECT";
    private final JdbcTemplate db;
    private static final String MATCH =
            """
m.s3_bucket=? AND (m.s3_key=? OR EXISTS
  (SELECT 1 FROM media_derivatives d WHERE d.media_id=m.id AND (d.thumbnail_key=? OR d.preview_key=?))
  OR EXISTS (SELECT 1 FROM media_upload_attempts a WHERE a.media_id=m.id AND a.storage_key=?))
""";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision inspect(String bucket, String key, String ownerId) {
        var rows =
                db.queryForList(
                        "SELECT m.id,m.s3_key,m.storage_status,m.media_status FROM drone_media m"
                            + " WHERE "
                                + MATCH
                                + " FOR UPDATE OF m SKIP LOCKED",
                        bucket,
                        key,
                        key,
                        key,
                        key);
        Integer count =
                db.queryForObject(
                        "SELECT count(*) FROM drone_media m WHERE " + MATCH,
                        Integer.class,
                        bucket,
                        key,
                        key,
                        key,
                        key);
        if (count == null || count != rows.size()) return Decision.DEFER;
        for (var row : rows) {
            if (Set.of("DELETE_PENDING", "DELETE_FAILED", "DELETED")
                            .contains(row.get("storage_status"))
                    || "REMOVED".equals(row.get("media_status"))) continue;
            String id = row.get("id").toString();
            if (key.equals(row.get("s3_key"))
                    || Boolean.TRUE.equals(
                            db.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM media_derivatives WHERE media_id=?"
                                        + " AND (thumbnail_key=? OR preview_key=?))",
                                    Boolean.class,
                                    id,
                                    key,
                                    key))) return Decision.REFERENCED;
            // A successful copy makes staging disposable; failed/obsolete attempts are disposable
            // too.
            if (Boolean.TRUE.equals(
                    db.queryForObject(
                            """
SELECT EXISTS(SELECT 1 FROM media_upload_attempts a WHERE media_id=? AND storage_key=?
  AND status IN ('PENDING','UPLOADED') AND a.attempt_number=
    (SELECT max(b.attempt_number) FROM media_upload_attempts b WHERE b.media_id=a.media_id))
""",
                            Boolean.class,
                            id,
                            key))) return Decision.REFERENCED;
        }
        return Decision.DELETE;
    }
}
