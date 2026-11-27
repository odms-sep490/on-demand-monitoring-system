package com.ondemandmonitoring.media.cleanup;

/**
 * Runs in a short database transaction, before storage I/O. Keys must be immutable and never
 * reused.
 */
public interface ObjectCleanupGuard {
    String type();

    Decision inspect(String bucket, String key, String ownerId);

    enum Decision {
        DELETE,
        DEFER,
        REFERENCED
    }
}
