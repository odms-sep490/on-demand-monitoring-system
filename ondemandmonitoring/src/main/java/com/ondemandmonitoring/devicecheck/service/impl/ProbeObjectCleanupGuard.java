package com.ondemandmonitoring.devicecheck.service.impl;

import com.ondemandmonitoring.media.cleanup.ObjectCleanupGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Only ephemeral, uniquely named probe artifacts are eligible. No business references exist. */
@Component
public class ProbeObjectCleanupGuard implements ObjectCleanupGuard {
    public static final String TYPE = "MEDIA_PROBE";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision inspect(String bucket, String key, String ownerId) {
        if (bucket == null || bucket.isBlank() || ownerId == null
                || !ownerId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid probe cleanup owner");
        }
        // MEDIA_PROBE tasks are created internally with a captured immutable key. Deployment
        // prefixes can change while a task is pending, so validate its identity, not live config.
        int executionSeparator = key == null ? -1 : key.lastIndexOf('/');
        int ownerSeparator = executionSeparator < 0 ? -1 : key.lastIndexOf('/', executionSeparator - 1);
        if (ownerSeparator <= 0 || !key.endsWith(".jpg")
                || !ownerId.equals(key.substring(ownerSeparator + 1, executionSeparator))) {
            throw new IllegalArgumentException("Invalid probe cleanup namespace");
        }
        String execution = key.substring(executionSeparator + 1, key.length() - 4);
        if (!UUID.fromString(execution).toString().equals(execution)) {
            throw new IllegalArgumentException("Invalid probe cleanup execution");
        }
        // The uploader locks the reservation across PUT/read/DELETE. A worker cannot claim
        // it until that transaction finishes; after a crash the database releases the lock.
        return Decision.DELETE;
    }

    static String objectPrefix(String storage, String diagnostic) {
        String storagePrefix = normalize(storage);
        String diagnosticPrefix = normalize(diagnostic);
        if (diagnosticPrefix.isBlank()) diagnosticPrefix = "diagnostics/media-probes";
        if (storagePrefix.isBlank() || diagnosticPrefix.equals(storagePrefix)
                || diagnosticPrefix.startsWith(storagePrefix + "/")) return diagnosticPrefix;
        return storagePrefix + "/" + diagnosticPrefix;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("^/+|/+$", "");
    }
}
