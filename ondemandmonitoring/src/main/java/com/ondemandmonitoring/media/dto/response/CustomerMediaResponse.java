package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;
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
public class CustomerMediaResponse {

    String mediaId;
    String missionId;
    String deviceId;
    String mediaType;
    String sourceType;
    String fileName;
    String contentType;
    long fileSize;
    Instant capturedAt;
    Instant availableAt;
    /** Null until full payment and Manager delivery; AVAILABLE metadata remains visible. */
    String downloadUrl;
    String thumbnailUrl;
    String previewUrl;
}
