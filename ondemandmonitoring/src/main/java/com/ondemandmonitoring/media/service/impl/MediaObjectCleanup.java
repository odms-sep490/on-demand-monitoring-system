package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.cleanup.ObjectCleanupOutbox;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

/** Domain adapter; durable leases, retry policy and S3 I/O remain in the shared outbox. */
@Service
@RequiredArgsConstructor
public class MediaObjectCleanup {
    private final ObjectCleanupOutbox outbox;

    public String enqueue(String bucket, String key, String ownerId) {
        return outbox.enqueue(bucket, key, MediaObjectCleanupGuard.TYPE, ownerId);
    }

    public List<String> enqueue(String bucket, Collection<String> keys, String ownerId) {
        return keys.stream()
                .filter(Objects::nonNull)
                .filter(key -> !key.isBlank())
                .distinct()
                .map(key -> enqueue(bucket, key, ownerId))
                .toList();
    }

    public String reserveUpload(String bucket, String key, String ownerId) {
        return outbox.reserveUpload(bucket, key, MediaObjectCleanupGuard.TYPE, ownerId);
    }

    public void protectReservation(String id) {
        outbox.protectReservation(id);
    }

    public void cancelReservation(String id) {
        outbox.cancelReservation(id);
    }

    public boolean completed(List<String> taskIds) {
        return taskIds.stream().allMatch(id -> "COMPLETED".equals(outbox.status(id)));
    }

    public boolean failed(List<String> taskIds) {
        return taskIds.stream().anyMatch(id -> "DEAD".equals(outbox.status(id)));
    }
}
