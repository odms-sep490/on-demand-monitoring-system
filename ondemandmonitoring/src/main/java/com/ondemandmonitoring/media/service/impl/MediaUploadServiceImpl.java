package com.ondemandmonitoring.media.service.impl;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.service.IDeviceService;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.request.CompleteMultipartRequest;
import com.ondemandmonitoring.media.dto.request.PrepareMediaUploadRequest;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest;
import com.ondemandmonitoring.media.dto.response.ManualMediaUploadResponse;
import com.ondemandmonitoring.media.dto.response.MediaUploadResponse;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.IMediaUploadService;
import com.ondemandmonitoring.mission.dto.response.MissionMediaContext;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaUploadServiceImpl implements IMediaUploadService {

    private static final long PART_SIZE = 8L * 1024 * 1024;
    private static final long MULTIPART_THRESHOLD = 16L * 1024 * 1024;
    private final IMissionMediaAccessService missionAccess;
    private final IDeviceService devices;
    private final MediaAssetRepository media;
    private final MediaUploadAttemptRepository attempts;
    private final ManualUploadTaskRepository manualTasks;
    private final ManualUploadTaskServiceImpl manualTaskService;
    private final MediaAuditLogRepository auditLogs;
    private final IMediaObjectStorage storage;
    private final AuthenticatedUserResolver currentUser;
    private final IMissionMediaEvidenceGuard missionLock;
    private final MediaUploadPolicy policy;

    @Value("${app.media.max-image-bytes:26214400}")
    private long maxImageBytes;
    @Value("${app.media.max-video-bytes:1073741824}")
    private long maxVideoBytes;

    @Override
    @Transactional(readOnly = true)
    public List<ManualMediaUploadResponse> manualTasks(String missionId) {
        MissionMediaContext mission = missionAccess.authorizeOperator(missionId);
        return manualTasks.findOpenByMissionId(mission.getId()).stream()
                .map(this::toManualResponse)
                .toList();
    }

    @Override
    @Transactional
    public MediaUploadResponse prepareManualFile(String mediaId, ManualMediaFileRequest request) {
        missionLock.lock(mediaId);
        MediaAsset asset = media.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        missionAccess.requireUploadPermission(asset.getMissionId());
        if (!asset.getFileSize().equals(request.getFileSize())
                || !asset.getContentType().equalsIgnoreCase(request.getContentType())
                || !asset.getChecksumSha256().equalsIgnoreCase(request.getChecksumSha256())) {
            throw new ApiException(ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT,
                    "Selected file is not an exact copy of the original capture");
        }
        if (manualTasks.findByMediaId(mediaId).isEmpty()) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID, "No manual task exists");
        }
        MediaUploadResponse response = retry(mediaId, true);
        audit(asset, null, "PC_BACKUP_SELECTED", "Exact backup selected for manual upload");
        return response;
    }

    @Override
    @Transactional
    public MediaUploadResponse prepare(String missionId, PrepareMediaUploadRequest request) {
        MissionMediaContext mission = missionAccess.authorizeOperator(missionId);
        missionLock.lockMission(mission.getId());
        if (!mission.isCaptureAllowed()) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_NOT_ALLOWED,
                    "Mission is not in a capture state (status=" + mission.getStatus()
                            + "); upload needs an accepted Pilot or Inspector in an eligible mission phase");
        }
        MissionDeviceAssignment deviceAssignment = requireAssignedDeviceAssignment(mission, request.getDeviceId());
        Device device = deviceAssignment.getDevice();
        validateMetadata(request, mission);
        Optional<MediaAsset> existing = media.findByMissionIdAndDeviceIdAndLocalMediaId(
                mission.getId(), device.getId(), request.getLocalMediaId());
        if (existing.isPresent()) {
            MediaAsset captured = existing.get();
            if (!java.util.Objects.equals(captured.getSourceType(), request.getSourceType())
                    || !captured.getType().equals(request.getMediaType())
                    || !captured.getContentType().equals(request.getContentType())
                    || !captured.getFileSize().equals(request.getFileSize())
                    || !captured.getChecksumSha256().equalsIgnoreCase(request.getChecksumSha256())) {
                throw new ApiException(ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT);
            }
            if (captured.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED)
                return newAttempt(captured, false);
            requireStorageMutable(captured);
            captured.setOrphanCleanupRequestedAt(null);
            return existingResponse(captured);
        }
        requireRecentCapture(request, mission);
        if (storage.bucket() == null || storage.bucket().isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "S3 bucket is required");
        }
        // The same file cannot count twice: attach the media that is already stored instead of uploading it again.
        if (media.existsActiveDuplicate(mission.getId(), request.getChecksumSha256())) {
            throw new ApiException(ErrorCode.MEDIA_DUPLICATE_CONTENT);
        }
        MediaAsset captured = new MediaAsset();
        captured.setDeviceAssignment(deviceAssignment);
        captured.setMission(deviceAssignment.getMission());
        captured.setLocalMediaId(request.getLocalMediaId());
        captured.setOperatorId(actor());
        captured.setSourceType(request.getSourceType());
        captured.setType(request.getMediaType());
        captured.setStorageProvider("S3");
        captured.setOriginalFileName(request.getFileName().replaceAll("[^A-Za-z0-9._-]", "_"));
        captured.setContentType(request.getContentType());
        captured.setFileSize(request.getFileSize());
        captured.setChecksumSha256(request.getChecksumSha256().toLowerCase(Locale.ROOT));
        captured.setCapturedAt(request.getCapturedAt());
        captured.setMediaStatus(MediaStatus.UPLOAD_PENDING);
        captured.setS3Bucket(storage.bucket());
        captured.setS3Key("staging/pending/" + UUID.randomUUID());
        captured.setS3Url("s3://" + storage.bucket() + "/" + captured.getS3Key());
        captured = media.saveAndFlush(captured);
        audit(captured, null, "PREPARED", null);

        return newAttempt(captured, false);
    }

    @Override
    @Transactional
    public MediaUploadResponse retry(String mediaId, boolean manual) {
        missionLock.lock(mediaId);
        MediaAsset captured = requireMedia(mediaId);
        missionAccess.requireUploadPermission(captured.getMissionId());

        if (captured.getMediaStatus() == MediaStatus.AVAILABLE) {
            return status(mediaId);
        }

        if (manual && captured.getMediaStatus() != MediaStatus.MANUAL_UPLOAD_REQUIRED) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID,
                    "Manual upload is not required");
        }

        if (!manual && captured.getMediaStatus() != MediaStatus.RETRY_REQUIRED) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID,
                    "Media is not ready for retry");
        }

        // Only genuine failures escalate; a retry that is merely prepared must never consume budget.
        if (!manual && policy.failedAutomaticAttemptsExhausted(failedAutomaticAttempts(mediaId))) {
            return requireManualUpload(captured,
                    "Automatic attempts exhausted");
        }

        return newAttempt(captured, manual);
    }

    @Override
    @Transactional
    public MediaUploadResponse reportFailure(String mediaId,
            String attemptId,
            ReportUploadFailureRequest request) {
        missionLock.lock(mediaId);

        MediaAsset captured = requireMedia(mediaId);
        missionAccess.requireUploadPermission(captured.getMissionId());
        MediaUploadAttempt attempt = requireAttempt(mediaId, attemptId);

        if (attempt.getStatus() == UploadAttemptStatus.FAILED) {
            return status(mediaId);
        }

        if (attempt.getStatus() != UploadAttemptStatus.PENDING) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID);
        }

        attempt.setStatus(UploadAttemptStatus.FAILED);
        attempt.setFailureCode(request.getCode());
        attempt.setFailureMessage(request.getMessage());
        attempt.setCompletedAt(Instant.now());
        attempts.save(attempt);
        if (attempt.getMultipartUploadId() != null) {
            try {
                storage.abortMultipartUpload(
                        attempt.getStorageKey(),
                        attempt.getMultipartUploadId());
            } catch (RuntimeException ignored) {

            }
        }

        captured.setValidationError(request.getMessage());
        audit(captured, attempt, "UPLOAD_FAILED", request.getCode());
        // The attempt was just persisted as FAILED, so it is already part of the tally.
        if (policy.failedAutomaticAttemptsExhausted(failedAutomaticAttempts(mediaId))) {
            return requireManualUpload(captured, request.getMessage());
        }

        captured.setMediaStatus(MediaStatus.RETRY_REQUIRED);
        media.save(captured);

        return status(mediaId);
    }

    @Override
    @Transactional(readOnly = true)
    public MediaUploadResponse status(String mediaId) {

        MediaAsset captured = requireMedia(mediaId);
        missionAccess.authorizeOperator(captured.getMissionId());
        MediaUploadAttempt latest = attempts
                .findFirstByMediaIdOrderByAttemptNumberDesc(mediaId).orElse(null);

        return new MediaUploadResponse(mediaId, latest == null ? null : latest.getId(),
                latest == null ? 0 : latest.getAttemptNumber(), effectiveStatus(captured), null,
                null, Map.of(), 0, 0, null,
                manualTasks.findByMediaId(mediaId).map(ManualUploadTask::getId).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public MediaUploadResponse presignPart(String mediaId, String attemptId, int partNumber) {
        MediaAsset captured = requireMedia(mediaId);
        missionAccess.requireUploadPermission(captured.getMissionId());
        MediaUploadAttempt attempt = requireAttempt(mediaId, attemptId);
        int count = partCount(captured.getFileSize());

        if (attempt.getStatus() != UploadAttemptStatus.PENDING
                || attempt.getMultipartUploadId() == null
                || partNumber < 1 || partNumber > count) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID, "Invalid upload part");
        }

        var signed = storage
                .createPresignedPartUrl(attempt.getStorageKey(),
                        attempt.getMultipartUploadId(), partNumber);

        return new MediaUploadResponse(
                mediaId,
                attemptId,
                attempt.getAttemptNumber(),
                effectiveStatus(captured),
                "MULTIPART", signed.url(), signed.headers(), PART_SIZE, count,
                Instant.now().plusSeconds(signed.expiresInSeconds()), null);
    }

    @Override
    @Transactional
    public void completeMultipart(String mediaId, String attemptId,
            CompleteMultipartRequest request) {
        missionLock.lock(mediaId);

        MediaAsset captured = requireMedia(mediaId);
        missionAccess.requireUploadPermission(captured.getMissionId());
        MediaUploadAttempt attempt = requireAttempt(mediaId, attemptId);

        if (attempt.getStatus() == UploadAttemptStatus.UPLOADED
                || attempt.getStatus() == UploadAttemptStatus.SUCCEEDED) {
            return;
        }

        if (attempt.getStatus() != UploadAttemptStatus.PENDING
                || attempt.getMultipartUploadId() == null) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID);
        }

        int expected = partCount(captured.getFileSize());
        List<CompleteMultipartRequest.Part> parts = request.getParts();
        if (parts.size() != expected) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Incomplete multipart upload");
        }

        Set<Integer> numbers = new HashSet<>();

        for (var part : parts) {
            if (part.getPartNumber() < 1 || part.getPartNumber() > expected
                    || !numbers.add(part.getPartNumber())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Duplicate or invalid part number");
            }
        }

        var completed = parts
                .stream()
                .sorted(Comparator.comparingInt(CompleteMultipartRequest.Part::getPartNumber))
                .map(part -> new IMediaObjectStorage.PartETag(part.getPartNumber(),
                        part.getETag()))
                .toList();
        storage.completeMultipartUpload(
                attempt.getStorageKey(),
                attempt.getMultipartUploadId(),
                completed);

        markUploaded(mediaId, attemptId);
    }

    @Override
    @Transactional
    public void markUploaded(String mediaId, String attemptId) {
        missionLock.lock(mediaId);
        MediaAsset captured = requireMedia(mediaId);
        missionAccess.requireUploadPermission(captured.getMissionId());
        MediaUploadAttempt attempt = requireAttempt(mediaId, attemptId);
        if (attempt.getStatus() == UploadAttemptStatus.SUCCEEDED
                || attempt.getStatus() == UploadAttemptStatus.UPLOADED
                || effectiveStatus(captured) == MediaStatus.AVAILABLE) {
            return;
        }
        if (attempt.getStatus() != UploadAttemptStatus.PENDING) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID);
        }
        attempt.setStatus(UploadAttemptStatus.UPLOADED);
        attempts.save(attempt);
        captured.setMediaStatus(MediaStatus.VALIDATING);
        media.save(captured);
        audit(captured, attempt, "UPLOAD_ACKNOWLEDGED", null);
    }

    private MediaUploadResponse newAttempt(MediaAsset captured, boolean manual) {
        requireStorageMutable(captured);
        var order = captured.getMission() == null ? null : captured.getMission().getOrder();
        if (order != null && order.getResultExpiresAt() != null && !order.getResultExpiresAt().isAfter(Instant.now()))
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_NOT_ALLOWED, "Result retention has expired");
        captured.setStorageStatus(com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE);
        captured.setOrphanCleanupRequestedAt(null);
        captured.setOrphanCleanupClaimedAt(null);
        captured.setDeletedFromStorageAt(null);
        captured.setStorageDeletionError(null);
        captured.setValidatedAt(null);
        captured.setValidationError(null);

        int number = attempts.findFirstByMediaIdOrderByAttemptNumberDesc(captured.getId())
                .map(previous -> previous.getAttemptNumber() + 1).orElse(1);
        String extension = switch (captured.getContentType()) {
            case "image/png" -> ".png";
            case "video/mp4" -> ".mp4";
            default -> ".jpg";
        };

        String prefix = storage.prefix().isBlank() ? "" : storage.prefix() + "/";

        String key = prefix + "staging/missions/" + captured.getMissionId() + "/devices/"
                + captured.getDeviceId() + "/" + captured.getId() + "/" + number + extension;

        MediaUploadAttempt attempt = new MediaUploadAttempt();
        attempt.setMedia(captured);
        attempt.setAttemptNumber(number);
        attempt.setStatus(UploadAttemptStatus.PENDING);
        attempt.setStorageKey(key);
        attempt.setManualAttempt(manual);
        attempt.setExpiresAt(Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()));
        String method;
        String url = null;
        Map<String, List<String>> headers = Map.of();

        long partSize = 0;
        int count = 0;

        Map<String, String> metadata = Map.of(
                "media-id",
                captured.getId(),
                "sha256",
                captured.getChecksumSha256());

        if (captured.getFileSize() >= MULTIPART_THRESHOLD) {
            attempt.setMultipartUploadId(
                    storage.createMultipartUpload(key, captured.getContentType(), metadata));
            attempt.setPartSizeBytes(PART_SIZE);
            method = "MULTIPART";
            partSize = PART_SIZE;
            count = partCount(captured.getFileSize());
        } else {
            var signed = storage.createPresignedPutUrl(
                    key,
                    captured.getContentType(),
                    captured.getFileSize(),
                    metadata);
            method = "PUT";
            url = signed.url();
            headers = signed.headers();
        }
        attempts.save(attempt);
        captured.setS3Key(key);
        captured.setS3Url("s3://" + storage.bucket() + "/" + key);
        captured.setMediaStatus(MediaStatus.UPLOAD_PENDING);
        media.save(captured);

        audit(captured, attempt, manual ? "MANUAL_UPLOAD_PREPARED"
                : "UPLOAD_PREPARED", null);

        return new MediaUploadResponse(
                captured.getId(),
                attempt.getId(),
                number,
                captured.getMediaStatus(),
                method,
                url,
                headers,
                partSize,
                count,
                attempt.getExpiresAt(),
                manualTasks
                        .findByMediaId(captured.getId()).map(ManualUploadTask::getId)
                        .orElse(null));
    }

    private MediaUploadResponse existingResponse(MediaAsset captured) {

        if (captured.getMediaStatus() != MediaStatus.UPLOAD_PENDING) {
            return status(captured.getId());
        }

        MediaUploadAttempt attempt = attempts.findFirstByMediaIdOrderByAttemptNumberDesc(captured.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID));

        if (attempt.getMultipartUploadId() != null) {
            return new MediaUploadResponse(
                    captured.getId(),
                    attempt.getId(),
                    attempt.getAttemptNumber(),
                    captured.getMediaStatus(),
                    "MULTIPART",
                    null,
                    Map.of(), PART_SIZE,
                    partCount(captured.getFileSize()), attempt.getExpiresAt(), null);
        }

        var signed = storage.createPresignedPutUrl(
                attempt.getStorageKey(),
                captured.getContentType(),
                captured.getFileSize(),
                Map.of("media-id",
                        captured.getId(),
                        "sha256",
                        captured.getChecksumSha256()));

        attempt.setExpiresAt(Instant.now().plusSeconds(signed.expiresInSeconds()));
        attempts.save(attempt);

        return new MediaUploadResponse(
                captured.getId(),
                attempt.getId(),
                attempt.getAttemptNumber(),
                captured.getMediaStatus(),
                "PUT",
                signed.url(),
                signed.headers(),
                0,
                0,
                attempt.getExpiresAt(),
                null);
    }

    private long failedAutomaticAttempts(String mediaId) {
        return attempts.countByMediaIdAndManualAttemptFalseAndStatus(
                mediaId, UploadAttemptStatus.FAILED);
    }

    private MediaUploadResponse requireManualUpload(MediaAsset captured, String reason) {

        captured.setMediaStatus(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        captured.setValidationError(reason);
        media.save(captured);
        manualTaskService.requireOpen(captured, reason);
        audit(captured, null, "MANUAL_UPLOAD_REQUIRED", reason);

        return status(captured.getId());
    }

    /** Photos older than the mission's own planned duration (counted back from now) cannot belong to this mission. */
    private void requireRecentCapture(PrepareMediaUploadRequest request, MissionMediaContext mission) {
        if (mission.getScheduledStartAt() == null || mission.getScheduledEndAt() == null
                || !mission.getScheduledEndAt().isAfter(mission.getScheduledStartAt())) return;
        java.time.Duration planned = java.time.Duration.between(mission.getScheduledStartAt(), mission.getScheduledEndAt());
        if (request.getCapturedAt().isBefore(Instant.now().minus(planned))) {
            throw new ApiException(ErrorCode.MEDIA_CAPTURE_TOO_OLD,
                    "Ảnh/video được chụp cách đây hơn " + planned.toMinutes() + " phút (thời lượng dự kiến của Mission).");
        }
    }

    private void validateMetadata(PrepareMediaUploadRequest request, MissionMediaContext mission) {
        if (request.getSourceType() != null && !java.util.Set.of(
                "DRONE_CAMERA", "SATELLITE_SNAPSHOT", "MANUAL_UPLOAD").contains(request.getSourceType())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unsupported media source type");
        }
        if ((request.getMediaType().equals("IMAGE") && request.getContentType().equals("video/mp4"))
                || (request.getMediaType().equals("VIDEO")
                        && !request.getContentType().equals("video/mp4"))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Media type and content type disagree");
        }
        long limit = request.getMediaType().equals("IMAGE") ? maxImageBytes : maxVideoBytes;
        if (request.getFileSize() > limit) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Media exceeds configured size limit");
        }
    }

    private MissionDeviceAssignment requireAssignedDeviceAssignment(MissionMediaContext mission, String deviceId) {
        Device device = devices.getEntityById(deviceId);
        return missionAccess.requireDeviceAssignment(mission.getId(), device.getId());
    }

    private MediaAsset requireMedia(String mediaId) {
        return media.findById(mediaId).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
    }

    private MediaUploadAttempt requireAttempt(String mediaId, String attemptId) {
        return attempts.findByIdAndMediaId(attemptId, mediaId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID));
    }

    private int partCount(long size) {
        return Math.toIntExact((size + PART_SIZE - 1) / PART_SIZE);
    }

    private MediaStatus effectiveStatus(MediaAsset captured) {
        return captured.getMediaStatus() == null
                ? MediaStatus.AVAILABLE
                : captured.getMediaStatus();
    }

    private void requireStorageMutable(MediaAsset asset) {
        if (asset.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETE_PENDING
                || asset.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETE_FAILED)
            throw new ApiException(ErrorCode.MEDIA_EVIDENCE_PROTECTED, "Storage cleanup is in progress");
    }

    private String actor() {
        return currentUser.getCurrentUser().getId().toString();
    }

    private void audit(MediaAsset captured, MediaUploadAttempt attempt,
            String action, String detail) {
        MediaAuditLog log = new MediaAuditLog();
        log.setMedia(captured);
        log.setAttemptId(attempt == null ? null : attempt.getId());
        log.setActorId(actor());
        log.setAction(action);
        log.setDetail(detail);
        auditLogs.save(log);
    }

    private ManualMediaUploadResponse toManualResponse(ManualUploadTask task) {
        MediaAsset asset = task.getMedia();
        return ManualMediaUploadResponse.builder()
                .manualTaskId(task.getId())
                .backendMediaId(asset.getId())
                .localMediaId(asset.getLocalMediaId())
                .missionId(asset.getMissionId())
                .deviceId(asset.getDeviceId())
                .mediaType(asset.getType())
                .fileName(asset.getOriginalFileName())
                .contentType(asset.getContentType())
                .fileSize(asset.getFileSize())
                .checksumSha256(asset.getChecksumSha256())
                .capturedAt(asset.getCapturedAt())
                .status(asset.getMediaStatus().name())
                .reason(task.getReason())
                .build();
    }
}
