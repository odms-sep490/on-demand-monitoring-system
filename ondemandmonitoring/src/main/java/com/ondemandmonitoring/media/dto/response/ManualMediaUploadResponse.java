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
public class ManualMediaUploadResponse {

    String manualTaskId;
    String backendMediaId;
    String localMediaId;
    String missionId;
    String deviceId;
    String mediaType;
    String sourceType;
    String fileName;
    String contentType;
    Long fileSize;
    String checksumSha256;
    Instant capturedAt;
    String status;
    String reason;
}
