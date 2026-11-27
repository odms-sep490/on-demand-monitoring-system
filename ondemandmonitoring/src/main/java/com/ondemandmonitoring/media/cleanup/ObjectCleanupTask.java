package com.ondemandmonitoring.media.cleanup;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Durable deletion intent. No foreign keys: tasks must survive deletion of their owner.
 * Deduplication includes owner/guard, so shared objects can be retired independently by each owner.
 */
@Entity
@Table(
        name = "object_cleanup_outbox",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_object_cleanup_hash", columnNames = "object_hash"),
        indexes = {
            @Index(
                    name = "ix_object_cleanup_ready",
                    columnList = "status,next_attempt_at,lease_until"),
            @Index(name = "ix_object_cleanup_retention", columnList = "status,completed_at")
        })
public class ObjectCleanupTask {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "object_hash", length = 64, nullable = false, updatable = false)
    private String objectHash;

    @Column(nullable = false, length = 255, updatable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 1024, updatable = false)
    private String objectKey;

    @Column(name = "guard_type", nullable = false, length = 64, updatable = false)
    private String guardType;

    @Column(name = "owner_id", nullable = false, length = 255, updatable = false)
    private String ownerId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 100)
    private String lastError;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
