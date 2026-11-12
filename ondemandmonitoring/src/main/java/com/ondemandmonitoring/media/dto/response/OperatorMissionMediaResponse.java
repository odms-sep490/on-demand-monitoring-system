package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.MediaUploadChannel;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OperatorMissionMediaResponse {

    String mediaId;
    Long version;
    boolean staffCreated;
    String missionId;
    String deviceId;
    String mediaType;
    String sourceType;
    String fileName;
    String contentType;
    long fileSize;
    Instant capturedAt;
    String captureTimeSource;
    String captureTimeZone;
    Instant metadataCapturedAt;
    String metadataTimeZone;
    String captureTimeConfirmedBy;
    Instant captureTimeConfirmedAt;
    String captureTimeConfirmationReason;
    Instant availableAt;
    MediaStatus status;
    String downloadUrl;
    String thumbnailUrl;
    String previewUrl;
    Instant urlExpiresAt;
    String localMediaId;
    String checksumSha256;
    MediaUploadChannel uploadChannel;
    Integer attemptNumber;
    String failureCode;
    String failureMessage;
    String manualTaskId;
}
