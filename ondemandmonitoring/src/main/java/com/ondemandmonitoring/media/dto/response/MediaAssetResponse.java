package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MediaAssetResponse {

    String id;
    String deviceId;
    String missionId;
    String type;
    String storageProvider;
    String originalFileName;
    String contentType;
    Long fileSize;
    String s3Bucket;
    String s3Key;
    String s3Url;
    Instant capturedAt;
    Instant createdAt;
    String sourceType;
    String sourceReferenceId;
    Double sourceDistanceMeters;
}
