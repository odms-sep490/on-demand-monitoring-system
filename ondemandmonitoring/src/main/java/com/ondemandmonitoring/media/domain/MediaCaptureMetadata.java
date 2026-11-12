package com.ondemandmonitoring.media.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/** Owned detail of MediaAsset. Writers lock Mission -> MediaAsset before changing this row. */
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "media_capture_metadata")
public class MediaCaptureMetadata {
    @Id
    @Column(name = "media_id", length = 255)
    @Setter(AccessLevel.NONE)
    private String mediaId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", foreignKey = @ForeignKey(name = "fk_media_capture_metadata_media"))
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Setter(AccessLevel.NONE)
    private MediaAsset media;

    @Version
    @Column(name = "version", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long version;

    MediaCaptureMetadata(MediaAsset media) { this.media = media; }

    @Column(name = "capture_time_source", length = 30)
    String captureTimeSource;

    @Column(name = "capture_time_zone", length = 40)
    String captureTimeZone;

    @Column(name = "metadata_captured_at")
    Instant metadataCapturedAt;

    @Column(name = "metadata_time_zone", length = 40)
    String metadataTimeZone;

    @Column(name = "capture_time_confirmed_by", length = 255)
    String captureTimeConfirmedBy;

    @Column(name = "capture_time_confirmed_at")
    Instant captureTimeConfirmedAt;

    @Column(name = "capture_time_confirmation_reason", length = 500)
    String captureTimeConfirmationReason;
}
