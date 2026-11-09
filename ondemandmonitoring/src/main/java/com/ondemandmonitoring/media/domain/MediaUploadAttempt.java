package com.ondemandmonitoring.media.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.Getter;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "media_upload_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_media_attempt_number", columnNames = {"media_id", "attempt_number"}),
        @UniqueConstraint(name = "uk_media_attempt_storage_key", columnNames = "storage_key")
})
public class MediaUploadAttempt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    MediaAsset media;

    @Column(name = "attempt_number", nullable = false)
    int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    UploadAttemptStatus status;

    @Column(name = "storage_key", nullable = false, length = 700)
    String storageKey;

    @Column(name = "multipart_upload_id", length = 500)
    String multipartUploadId;

    @Column(name = "part_size_bytes")
    Long partSizeBytes;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "completed_at")
    Instant completedAt;

    @Column(name = "failure_code", length = 100)
    String failureCode;

    @Column(name = "failure_message", length = 1000)
    String failureMessage;

    @Column(name = "manual_attempt", nullable = false)
    boolean manualAttempt;
}
