package com.ondemandmonitoring.media.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;

/** Owned detail of MediaAsset. Writers lock Mission -> MediaAsset before changing this row. */
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "media_derivatives", indexes = @Index(name = "ix_media_derivative_state",
        columnList = "derivative_status,derivative_processed_at,media_id"))
public class MediaDerivative {
    @Id
    @Column(name = "media_id", length = 255)
    @Setter(AccessLevel.NONE)
    private String mediaId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", foreignKey = @ForeignKey(name = "fk_media_derivatives_media"))
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Setter(AccessLevel.NONE)
    private MediaAsset media;

    @Version
    @Column(name = "version", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long version;

    MediaDerivative(MediaAsset media) { this.media = media; }

    @Enumerated(EnumType.STRING)
    @Column(name = "derivative_status", nullable = false, length = 24)
    MediaDerivativeStatus derivativeStatus = MediaDerivativeStatus.PENDING;

    @Column(name = "thumbnail_key", length = 1000)
    String thumbnailKey;

    @Column(name = "preview_key", length = 1000)
    String previewKey;

    @Column(name = "thumbnail_size_bytes")
    Long thumbnailSizeBytes;

    @Column(name = "preview_size_bytes")
    Long previewSizeBytes;

    @Column(name = "derivative_attempt_count", nullable = false)
    int derivativeAttemptCount;

    @Column(name = "derivative_processed_at")
    Instant derivativeProcessedAt;

    @Column(name = "derivative_error_code", length = 80)
    String derivativeErrorCode;
}
