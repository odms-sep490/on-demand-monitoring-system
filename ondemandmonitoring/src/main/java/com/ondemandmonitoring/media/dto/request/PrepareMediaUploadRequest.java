package com.ondemandmonitoring.media.dto.request;

import jakarta.validation.constraints.*;
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
public class PrepareMediaUploadRequest {

    @NotBlank
    @Size(max = 50)
    String deviceId;

    @NotBlank
    @Size(max = 100)
    String localMediaId;

    @NotBlank
    @Pattern(regexp = "IMAGE|VIDEO")
    String mediaType;

    @NotBlank
    @Size(max = 255)
    String fileName;

    @NotBlank
    @Pattern(regexp = "image/jpeg|image/png|video/mp4")
    String contentType;

    @NotNull
    @Positive
    Long fileSize;

    @NotBlank
    @Pattern(regexp = "[a-fA-F0-9]{64}")
    String checksumSha256;

    @NotNull
    @PastOrPresent
    Instant capturedAt;

    /** Optional for legacy captures whose provenance is unknown. */
    @Pattern(regexp = "DRONE_CAMERA|SATELLITE_SNAPSHOT|MANUAL_UPLOAD")
    String sourceType;

    public PrepareMediaUploadRequest(String deviceId, String localMediaId, String mediaType,
            String fileName, String contentType, Long fileSize, String checksumSha256, Instant capturedAt) {
        this(deviceId, localMediaId, mediaType, fileName, contentType, fileSize, checksumSha256, capturedAt, null);
    }
}
