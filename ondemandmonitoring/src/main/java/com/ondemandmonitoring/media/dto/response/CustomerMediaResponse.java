package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;
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
    /** Legacy field; original download URLs are issued only by the gated download-url endpoint. */
    String downloadUrl;
    String thumbnailUrl;
    String previewUrl;
    MediaDerivativeStatus derivativeStatus;
    boolean canDownloadOriginal;
}
