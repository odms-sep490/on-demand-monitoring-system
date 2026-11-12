package com.ondemandmonitoring.media.service.impl;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.enums.MediaUploadChannel;
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
        requireStorageMutable(asset);
        if (!asset.getFileSize().equals(request.getFileSize())
                || !asset.getContentType().equalsIgnoreCase(request.getContentType())
                || !asset.getChecksumSha256().equalsIgnoreCase(request.getChecksumSha256())) {
            throw new ApiException(ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT,
                    "Selected file is not an exact copy of the original capture");
        }
        if (asset.getMediaStatus() == MediaStatus.AVAILABLE
                || asset.getMediaStatus() == MediaStatus.PENDING_MANAGER_APPROVAL
                || asset.getMediaStatus() == MediaStatus.VALIDATING) return status(mediaId);
        if (asset.getMediaStatus() == MediaStatus.UPLOAD_PENDING) {
            var latest = attempts.findFirstByMediaIdOrderByAttemptNumberDesc(mediaId);
            if (latest.isPresent() && latest.get().getUploadChannel() == MediaUploadChannel.STAFF_FILE
                    && latest.get().getStatus() == UploadAttemptStatus.PENDING) return existingResponse(asset);
        }
        if (asset.getMediaStatus() != MediaStatus.MANUAL_UPLOAD_REQUIRED
                || manualTasks.findByMediaId(mediaId).filter(task -> task.getStatus()
                    == com.ondemandmonitoring.media.enums.ManualUploadTaskStatus.OPEN).isEmpty()) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_ATTEMPT_INVALID, "No manual task exists");
        }
        MediaUploadResponse response = newAttempt(asset, true, MediaUploadChannel.STAFF_FILE);
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
            if (captured.getMediaStatus() == MediaStatus.REMOVED) throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
            if (captured.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED)
                return newAttempt(captured, false, MediaUploadChannel.FLIGHT_CONTROLLER);
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
        captured.setSourceType(request.getSourceType() == null ? "DRONE_CAMERA" : request.getSourceType());
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

        return newAttempt(captured, false, MediaUploadChannel.FLIGHT_CONTROLLER);
    }

    @Override
    @Transactional
    public MediaUploadResponse prepareStaffFile(String missionId, PrepareMediaUploadRequest request) {
        MissionMediaContext mission = missionAccess.authorizeOperator(missionId);
        missionAccess.requireUploadPermission(mission.getId());
        missionLock.lockMission(mission.getId());
        MissionDeviceAssignment deviceAssignment = requireAssignedDeviceAssignment(mission, request.getDeviceId());
        validateMetadata(request, mission);
        requireCaptureWithinMission(request, mission);

        Optional<MediaAsset> existing = media.findByMissionIdAndDeviceIdAndLocalMediaId(
                mission.getId(), deviceAssignment.getDevice().getId(), request.getLocalMediaId());
        if (existing.isPresent()) {
            MediaAsset captured = existing.get();
            if (!captured.getType().equals(request.getMediaType())
                    || !captured.getContentType().equals(request.getContentType())
                    || !captured.getFileSize().equals(request.getFileSize())
                    || !captured.getChecksumSha256().equalsIgnoreCase(request.getChecksumSha256())
                    || !"DRONE_CAMERA".equals(captured.getSourceType())) {
                throw new ApiException(ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT);
            }
            if (captured.getMediaStatus() == MediaStatus.REMOVED) {
                throw new ApiException(ErrorCode.MEDIA_REMOVED);
            }
            requireStorageMutable(captured);
            if (captured.getMediaStatus() == MediaStatus.MANUAL_UPLOAD_REQUIRED) {
                declareStaffCaptureTime(captured, request);
                return newAttempt(captured, true, MediaUploadChannel.STAFF_FILE);
            }
            if (Math.abs(java.time.Duration.between(captured.getCapturedAt(), request.getCapturedAt()).getSeconds()) > 2)
                throw new ApiException(ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT, "Capture time cannot change during an active or completed upload");
            return existingResponse(captured);
        }

        if (storage.bucket() == null || storage.bucket().isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "S3 bucket is required");
        }
        if (media.existsActiveDuplicate(mission.getId(), request.getChecksumSha256())) {
            throw new ApiException(ErrorCode.MEDIA_DUPLICATE_CONTENT);
        }

        MediaAsset captured = new MediaAsset();
        captured.setDeviceAssignment(deviceAssignment);
        captured.setMission(deviceAssignment.getMission());
        captured.setLocalMediaId(request.getLocalMediaId());
        captured.setOperatorId(actor());
        // Capture provenance stays DRONE_CAMERA; STAFF_FILE records the transport channel.
        captured.setSourceType("DRONE_CAMERA");
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
        declareStaffCaptureTime(captured, request);
        captured = media.saveAndFlush(captured);
        audit(captured, null, "STAFF_FILE_UPLOAD_PREPARED", "Drone capture selected from local media");
        return newAttempt(captured, true, MediaUploadChannel.STAFF_FILE);
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

        return newAttempt(captured, manual, MediaUploadChannel.FLIGHT_CONTROLLER);
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
        if (attempt.isManualAttempt() || attempt.getUploadChannel() == MediaUploadChannel.STAFF_FILE) {
            return requireManualUpload(captured, request.getMessage());
        }
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

        MediaAsset captured = media.findById(mediaId).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        missionAccess.authorizeOperator(captured.getMissionId());
        MediaUploadAttempt latest = attempts
                .findFirstByMediaIdOrderByAttemptNumberDesc(mediaId).orElse(null);

        return new MediaUploadResponse(mediaId, latest == null ? null : latest.getId(),
                latest == null ? 0 : latest.getAttemptNumber(), effectiveStatus(captured), null,
                null, Map.of(), 0, 0, null,
                manualTasks.findByMediaId(mediaId).map(ManualUploadTask::getId).orElse(null),
                latest == null ? null : latest.getUploadChannel(),
                latest == null ? captured.getValidationError() : latest.getFailureCode(),
                latest == null ? captured.getValidationError() : latest.getFailureMessage());
    }

    @Override
    @Transactional
    public MediaUploadResponse presignPart(String mediaId, String attemptId, int partNumber) {
        missionLock.lock(mediaId);
        MediaAsset captured = media.findByIdForUpdate(mediaId).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        requireStorageMutable(captured);
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

        Instant expiresAt = Instant.now().plusSeconds(signed.expiresInSeconds());
        if (attempt.getExpiresAt() == null || attempt.getExpiresAt().isBefore(expiresAt)) attempt.setExpiresAt(expiresAt);
        return new MediaUploadResponse(
                mediaId,
                attemptId,
                attempt.getAttemptNumber(),
                effectiveStatus(captured),
                "MULTIPART", signed.url(), signed.headers(), PART_SIZE, count,
                expiresAt, null,
                attempt.getUploadChannel(), attempt.getFailureCode(), attempt.getFailureMessage());
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

    private MediaUploadResponse newAttempt(MediaAsset captured, boolean manual, MediaUploadChannel channel) {
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
        attempt.setUploadChannel(channel);
        attempt.setUploadedBy(actor());
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
                : "UPLOAD_PREPARED", channel == MediaUploadChannel.STAFF_FILE
                        ? "captureTime=" + captured.getCapturedAt() + ";source=" + captured.getCaptureTimeSource()
                          + ";zone=" + captured.getCaptureTimeZone() + ";reason=" + captured.getCaptureTimeConfirmationReason()
                        : null);

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
                        .orElse(null),
                attempt.getUploadChannel(), attempt.getFailureCode(), attempt.getFailureMessage());
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
                    partCount(captured.getFileSize()), attempt.getExpiresAt(), null,
                    attempt.getUploadChannel(), attempt.getFailureCode(), attempt.getFailureMessage());
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
                null,
                attempt.getUploadChannel(), attempt.getFailureCode(), attempt.getFailureMessage());
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

    private void declareStaffCaptureTime(MediaAsset asset, PrepareMediaUploadRequest request) {
        String source = request.getCaptureTimeSource() == null ? "STAFF_CONFIRMED" : request.getCaptureTimeSource();
        if (!Set.of("METADATA", "STAFF_CONFIRMED").contains(source))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid capture time source");
        String zone = request.getCaptureTimeZone() == null ? "Asia/Ho_Chi_Minh" : request.getCaptureTimeZone();
        if (!zone.equals("Asia/Ho_Chi_Minh") && !zone.equals("UTC")) {
            try { java.time.ZoneOffset.of(zone); }
            catch (java.time.DateTimeException invalidZone) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid capture time zone");
            }
        }
        asset.setCapturedAt(request.getCapturedAt());
        asset.setCaptureTimeSource(source);
        asset.setCaptureTimeZone(zone);
        asset.setCaptureTimeConfirmedBy(actor());
        asset.setCaptureTimeConfirmedAt(Instant.now());
        String reason = request.getCaptureTimeConfirmationReason();
        if (request.getCaptureTimeSource() == null && reason == null) reason = "Staff declared capture time (legacy client)";
        if ("STAFF_CONFIRMED".equals(source) && (reason == null || reason.isBlank()))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Capture time confirmation reason is required");
        asset.setCaptureTimeConfirmationReason(reason);

    }

    /** Staff imports use the declared camera capture time, constrained to the Mission flight window. */
    private void requireCaptureWithinMission(PrepareMediaUploadRequest request, MissionMediaContext mission) {
        Instant start = mission.getActualStartAt() != null ? mission.getActualStartAt() : mission.getScheduledStartAt();
        Instant end = mission.getActualEndAt() != null ? mission.getActualEndAt() : mission.getScheduledEndAt();
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_NOT_ALLOWED,
                    "Mission has no valid flight window for Staff media import");
        }
        java.time.Duration tolerance = java.time.Duration.ofMinutes(30);
        if (request.getCapturedAt().isBefore(start.minus(tolerance))
                || request.getCapturedAt().isAfter(end.plus(tolerance))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Declared capture time is outside this Mission's flight window");
        }
    }

    private void validateMetadata(PrepareMediaUploadRequest request, MissionMediaContext mission) {
        if (request.getSourceType() != null && !"DRONE_CAMERA".equals(request.getSourceType())) {
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
        var asset = media.findById(mediaId).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        if (asset.getMediaStatus() == MediaStatus.REMOVED) throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        return asset;
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
        if (asset.getMediaStatus() == MediaStatus.REMOVED) throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
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
