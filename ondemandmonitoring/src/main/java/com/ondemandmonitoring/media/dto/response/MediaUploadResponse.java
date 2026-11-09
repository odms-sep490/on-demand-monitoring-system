package com.ondemandmonitoring.media.dto.response;

import com.ondemandmonitoring.media.enums.MediaStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
public class MediaUploadResponse {

    String mediaId;
    String attemptId;
    int attemptNumber;
    MediaStatus status;
    String uploadMethod;
    String uploadUrl;
    Map<String, List<String>> uploadHeaders;
    long partSizeBytes;
    int partCount;
    Instant expiresAt;
    String manualTaskId;
}
