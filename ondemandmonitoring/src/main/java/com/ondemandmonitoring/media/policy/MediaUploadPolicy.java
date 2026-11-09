package com.ondemandmonitoring.media.policy;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest;
import com.ondemandmonitoring.media.dto.request.PrepareMediaUploadRequest;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaUploadPolicy {
    /**
     * Staff escalation requires STRICTLY MORE THAN this many failed automatic upload attempts,
     * i.e. the ticket is raised by the fourth qualifying failure.
     */
    static int FAILED_AUTOMATIC_ATTEMPTS_BEFORE_MANUAL = 3;
    public static final long PART_SIZE_BYTES = 8L * 1024 * 1024;
    static long MULTIPART_THRESHOLD_BYTES = 16L * 1024 * 1024;

    @NonFinal
    @Value("${app.media.max-image-bytes:26214400}")
    long maxImageBytes;
    @NonFinal
    @Value("${app.media.max-video-bytes:1073741824}")
    long maxVideoBytes;

    /** Only genuinely failed automatic transfers count; preparing a URL or an untouched retry never does. */
    public boolean failedAutomaticAttemptsExhausted(long failedAutomaticAttempts) {
        return failedAutomaticAttempts > FAILED_AUTOMATIC_ATTEMPTS_BEFORE_MANUAL;
    }

    public MediaStatus failureStatus(boolean manual, long failedAutomaticAttempts) {
        return manual || failedAutomaticAttemptsExhausted(failedAutomaticAttempts)
                ? MediaStatus.MANUAL_UPLOAD_REQUIRED : MediaStatus.RETRY_REQUIRED;
    }

    public boolean useMultipart(long size) {
        return size >= MULTIPART_THRESHOLD_BYTES;
    }

    public int partCount(long size) {
        return Math.toIntExact((size + PART_SIZE_BYTES - 1) / PART_SIZE_BYTES);
    }

    public MediaStatus effectiveStatus(MediaAsset asset) {
        return asset.getMediaStatus() == null ? MediaStatus.AVAILABLE : asset.getMediaStatus();
    }

    public void validateMetadata(PrepareMediaUploadRequest request) {
        if (("IMAGE".equals(request.getMediaType()) && "video/mp4".equals(request.getContentType()))
                || ("VIDEO".equals(request.getMediaType()) && !"video/mp4".equals(request.getContentType()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Media type and content type disagree");
        }
        long limit = "IMAGE".equals(request.getMediaType()) ? maxImageBytes : maxVideoBytes;
        if (request.getFileSize() > limit) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Media exceeds configured size limit");
        }
    }

    public void requireExactBackup(MediaAsset asset, ManualMediaFileRequest request) {
        if (!asset.getFileSize().equals(request.getFileSize())
                || !asset.getContentType().equalsIgnoreCase(request.getContentType())
                || !asset.getChecksumSha256().equalsIgnoreCase(request.getChecksumSha256())) {
            throw new ApiException(ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT,
                    "Selected file is not an exact copy of the original capture");
        }
    }
}
