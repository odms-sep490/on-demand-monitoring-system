package com.ondemandmonitoring.service.service.impl;

import com.ondemandmonitoring.media.cleanup.ObjectCleanupGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runs inside the cleanup worker's short transaction; uploads retain this owner lock until commit. */
@Component
@RequiredArgsConstructor
public class ServiceImageCleanupGuard implements ObjectCleanupGuard {
    public static final String TYPE = "SERVICE_IMAGE";
    private final JdbcTemplate db;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision inspect(String bucket, String key, String ownerId) {
        var locked = db.queryForList("SELECT id FROM services WHERE id = ? FOR UPDATE SKIP LOCKED", ownerId);
        if (locked.isEmpty() && Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM services WHERE id = ?)", Boolean.class, ownerId))) {
            return Decision.DEFER;
        }
        // Include other owners: historical/shared references must also protect the object.
        boolean referenced = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM services WHERE image_s3_bucket = ? AND image_s3_key = ?)",
                Boolean.class, bucket, key));
        return referenced ? Decision.REFERENCED : Decision.DELETE;
    }
}
