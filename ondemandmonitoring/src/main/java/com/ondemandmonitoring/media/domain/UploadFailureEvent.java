package com.ondemandmonitoring.media.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.enums.UploadFailureCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

/**
 * Durable ledger of client-reported upload-failure events.
 * <p>
 * A client that cannot reach the Backend keeps the event in its own durable journal and
 * replays it later, so this table is the Backend-side record that an event was durably
 * accepted. {@code eventKey} is namespaced ({@code upload-failure:<eventId>}) and unique, so
 * concurrent or repeated delivery of the same event collapses onto one row instead of
 * double-counting a failure.
 * <p>
 * {@code processedAt == null} means the event was accepted but not yet applied, which keeps
 * it retryable. This is deliberately NOT a reuse of {@code media_storage_event_inbox}: that
 * table asserts a bucket and object key, which would falsely assert an object exists for a
 * transfer that never happened.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Check(name = "ck_upload_failure_category",
        constraints = "category IN ('TRANSFER_FAILED', 'TRANSFER_ACK_LOST')")
@Check(name = "ck_upload_failure_outcome",
        constraints = "processed_at IS NULL OR processing_outcome IS NOT NULL")
@Table(name = "media_upload_failure_events", uniqueConstraints =
        @UniqueConstraint(name = "uk_media_upload_failure_event", columnNames = "event_key"))
public class UploadFailureEvent extends BaseEntity {

    /** Namespaced idempotency key; never reuse the S3 storage-event namespace. */
    @Column(name = "event_key", nullable = false, length = 64)
    String eventKey;

    @Column(name = "mission_id", nullable = false)
    String missionId;

    @Column(name = "media_id", nullable = false)
    String mediaId;

    @Column(name = "attempt_id", nullable = false)
    String attemptId;

    /** Monotonic per media asset; proves an attempt row existed for this event. */
    @Column(name = "attempt_number", nullable = false)
    Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    UploadFailureCategory category;

    /** Client-reported instant the failure was observed. */
    @Column(name = "occurred_at", nullable = false)
    Instant occurredAt;

    @Column(name = "failure_code", length = 100)
    String failureCode;

    @Column(name = "failure_message", length = 1000)
    String failureMessage;

    /** NULL until the event has been applied; a non-NULL value is what the client awaits. */
    @Column(name = "processed_at")
    Instant processedAt;

    @Column(name = "processing_outcome", length = 40)
    String processingOutcome;

    public boolean isProcessed() {
        return processedAt != null;
    }
}
