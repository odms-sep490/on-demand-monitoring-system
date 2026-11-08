package com.ondemandmonitoring.media.dto.response;

import com.ondemandmonitoring.media.enums.MediaStatus;
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
public class MediaResponse {

    String id;
    String missionId;
    String deviceId;
    String type;
    MediaStatus status;
    String url;
    String thumbnailUrl;
    String previewUrl;
    Long expiresIn;
    String contentType;
    Long fileSize;
    Instant capturedAt;
    String sourceType;
    String sourceReferenceId;
    Instant sourceCapturedAt;
    Double sourceLatitude;
    Double sourceLongitude;
    Double captureLatitude;
    Double captureLongitude;
    Double captureAltitudeM;
    Double sourceDistanceMeters;
}
