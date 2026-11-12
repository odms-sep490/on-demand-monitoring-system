package com.ondemandmonitoring.media.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "drone_media", uniqueConstraints = @UniqueConstraint(name = "uk_drone_media_local_capture", columnNames = {
        "mission_id", "device_id", "local_media_id" }))
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MediaAsset extends BaseEntity {


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false, insertable = false, updatable = false)
    Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "mission_id", referencedColumnName = "mission_id", nullable = false),
            @JoinColumn(name = "device_id", referencedColumnName = "device_id", nullable = false)
    })
    MissionDeviceAssignment deviceAssignment;

    @Column(name = "media_type", nullable = false, length = 50)
    String type;

    @Column(name = "storage_provider", nullable = false, length = 50)
    String storageProvider;

    @Column(name = "original_file_name", nullable = false)
    String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    String contentType;

    @Column(name = "file_size", nullable = false)
    Long fileSize;

    @Column(name = "s3_bucket", nullable = false)
    String s3Bucket;

    @Column(name = "s3_key", nullable = false, unique = true)
    String s3Key;

    @Column(name = "s3_url", nullable = false, length = 1000)
    String s3Url;

    @Column(name = "captured_at", nullable = false)
    Instant capturedAt;

    @Column(name = "local_media_id", length = 100)
    String localMediaId;

    @Column(name = "operator_id", length = 100)
    String operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_status", length = 40)
    MediaStatus mediaStatus;

    @Column(name = "checksum_sha256", length = 64)
    String checksumSha256;

    @Column(name = "validation_error", length = 1000)
    String validationError;

    @Column(name = "validated_at")
    Instant validatedAt;

    @Column(name = "available_at")
    Instant availableAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_status", nullable = false, length = 30)
    MediaStorageStatus storageStatus = MediaStorageStatus.AVAILABLE;

    @Column(name = "deleted_from_storage_at")
    Instant deletedFromStorageAt;

    @Column(name = "storage_deletion_error", length = 500)
    String storageDeletionError;

    @Column(name = "orphan_cleanup_requested_at")
    Instant orphanCleanupRequestedAt;

    @Column(name = "orphan_cleanup_claimed_at")
    Instant orphanCleanupClaimedAt;

    /** DRONE_CAMERA / SATELLITE_SNAPSHOT / MANUAL_UPLOAD. Null means unknown legacy provenance. */
    @Column(name = "source_type", length = 40)
    String sourceType;

    @Column(name = "source_reference_id", length = 100)
    String sourceReferenceId;

    @Column(name = "source_captured_at")
    Instant sourceCapturedAt;

    @Column(name = "source_latitude")
    Double sourceLatitude;

    @Column(name = "source_longitude")
    Double sourceLongitude;

    @Column(name = "capture_latitude")
    Double captureLatitude;

    @Column(name = "capture_longitude")
    Double captureLongitude;

    @Column(name = "capture_altitude_m")
    Double captureAltitudeM;

    @Column(name = "source_distance_meters")
    Double sourceDistanceMeters;

    /** Manager-controlled exposure in the pre-payment customer preview. */
    @Column(name = "preview_selected", nullable = false)
    boolean previewSelected;

    @OneToOne(mappedBy = "media", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Setter(AccessLevel.NONE)
    MediaCaptureMetadata captureMetadata;

    @OneToOne(mappedBy = "media", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Setter(AccessLevel.NONE)
    MediaDerivative derivative = new MediaDerivative(this);

    public String getCaptureTimeSource() { return captureMetadata == null ? null : captureMetadata.getCaptureTimeSource(); }

    public void setCaptureTimeSource(String value) { ensureMediaCaptureMetadata().setCaptureTimeSource(value); }

    public String getCaptureTimeZone() { return captureMetadata == null ? null : captureMetadata.getCaptureTimeZone(); }

    public void setCaptureTimeZone(String value) { ensureMediaCaptureMetadata().setCaptureTimeZone(value); }

    public Instant getMetadataCapturedAt() { return captureMetadata == null ? null : captureMetadata.getMetadataCapturedAt(); }

    public void setMetadataCapturedAt(Instant value) { ensureMediaCaptureMetadata().setMetadataCapturedAt(value); }

    public String getMetadataTimeZone() { return captureMetadata == null ? null : captureMetadata.getMetadataTimeZone(); }

    public void setMetadataTimeZone(String value) { ensureMediaCaptureMetadata().setMetadataTimeZone(value); }

    public String getCaptureTimeConfirmedBy() { return captureMetadata == null ? null : captureMetadata.getCaptureTimeConfirmedBy(); }

    public void setCaptureTimeConfirmedBy(String value) { ensureMediaCaptureMetadata().setCaptureTimeConfirmedBy(value); }

    public Instant getCaptureTimeConfirmedAt() { return captureMetadata == null ? null : captureMetadata.getCaptureTimeConfirmedAt(); }

    public void setCaptureTimeConfirmedAt(Instant value) { ensureMediaCaptureMetadata().setCaptureTimeConfirmedAt(value); }

    public String getCaptureTimeConfirmationReason() { return captureMetadata == null ? null : captureMetadata.getCaptureTimeConfirmationReason(); }

    public void setCaptureTimeConfirmationReason(String value) { ensureMediaCaptureMetadata().setCaptureTimeConfirmationReason(value); }

    private MediaCaptureMetadata ensureMediaCaptureMetadata() {
        if (captureMetadata == null) captureMetadata = new MediaCaptureMetadata(this);
        return captureMetadata;
    }

    public MediaDerivativeStatus getDerivativeStatus() { return derivative == null ? MediaDerivativeStatus.PENDING : derivative.getDerivativeStatus(); }

    public void setDerivativeStatus(MediaDerivativeStatus value) { ensureMediaDerivative().setDerivativeStatus(value); }

    public String getThumbnailKey() { return derivative == null ? null : derivative.getThumbnailKey(); }

    public void setThumbnailKey(String value) { ensureMediaDerivative().setThumbnailKey(value); }

    public String getPreviewKey() { return derivative == null ? null : derivative.getPreviewKey(); }

    public void setPreviewKey(String value) { ensureMediaDerivative().setPreviewKey(value); }

    public Long getThumbnailSizeBytes() { return derivative == null ? null : derivative.getThumbnailSizeBytes(); }

    public void setThumbnailSizeBytes(Long value) { ensureMediaDerivative().setThumbnailSizeBytes(value); }

    public Long getPreviewSizeBytes() { return derivative == null ? null : derivative.getPreviewSizeBytes(); }

    public void setPreviewSizeBytes(Long value) { ensureMediaDerivative().setPreviewSizeBytes(value); }

    public int getDerivativeAttemptCount() { return derivative == null ? 0 : derivative.getDerivativeAttemptCount(); }

    public void setDerivativeAttemptCount(int value) { ensureMediaDerivative().setDerivativeAttemptCount(value); }

    public Instant getDerivativeProcessedAt() { return derivative == null ? null : derivative.getDerivativeProcessedAt(); }

    public void setDerivativeProcessedAt(Instant value) { ensureMediaDerivative().setDerivativeProcessedAt(value); }

    public String getDerivativeErrorCode() { return derivative == null ? null : derivative.getDerivativeErrorCode(); }

    public void setDerivativeErrorCode(String value) { ensureMediaDerivative().setDerivativeErrorCode(value); }

    private MediaDerivative ensureMediaDerivative() {
        if (derivative == null) derivative = new MediaDerivative(this);
        return derivative;
    }

    public String getMissionId() {
        if (mission != null) {
            return mission.getId();
        }
        return deviceAssignment != null && deviceAssignment.getMission() != null
                ? deviceAssignment.getMission().getId()
                : null;
    }

    public void setMissionId(String missionId) {
        Mission missionRef = referenceMission(missionId);
        this.mission = missionRef;
        ensureDeviceAssignment().setMission(missionRef);
    }

    public Device getDevice() {
        return deviceAssignment == null ? null : deviceAssignment.getDevice();
    }

    public void setDevice(Device device) {
        ensureDeviceAssignment().setDevice(device);
    }

    public String getDeviceId() {
        Device device = getDevice();
        return device == null ? null : device.getId();
    }

    public void setDeviceId(String deviceId) {
        ensureDeviceAssignment().setDevice(referenceDevice(deviceId));
    }

    private MissionDeviceAssignment ensureDeviceAssignment() {
        if (deviceAssignment == null) {
            deviceAssignment = new MissionDeviceAssignment();
        }
        return deviceAssignment;
    }

    private Mission referenceMission(String missionId) {
        if (missionId == null) {
            return null;
        }
        Mission missionRef = new Mission();
        missionRef.setId(missionId);
        return missionRef;
    }

    private Device referenceDevice(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        Device deviceRef = new Device();
        deviceRef.setId(deviceId);
        return deviceRef;
    }
}
