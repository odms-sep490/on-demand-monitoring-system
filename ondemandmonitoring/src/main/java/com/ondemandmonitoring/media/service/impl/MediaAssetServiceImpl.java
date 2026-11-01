package com.ondemandmonitoring.media.service.impl;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.service.IDeviceService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage.StoredObject;
import com.ondemandmonitoring.media.service.IMediaObjectStorage.StoredObjectStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaAssetServiceImpl implements IMediaAssetService {

    static Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "video/mp4");
    static String MEDIA_TYPE_IMAGE = "IMAGE";
    static String MEDIA_TYPE_VIDEO = "VIDEO";
    static String STORAGE_PROVIDER_S3 = "S3";
    static String STORAGE_PROVIDER_LOCAL = "LOCAL";
    static Path LOCAL_IMAGE_DIR = Path.of("uploads", "device-images");

    IMediaObjectStorage objectStorage;
    IResultRetentionAccessService retentionAccess;
    Environment environment;
    IDeviceService deviceService;
    MediaAssetRepository mediaAssetRepository;
    IMissionMediaAccessService missionAccess;
    IMissionMediaEvidenceGuard missionLock;

    @Transactional
    @Override
    public MediaAsset upload(String missionId, String deviceId,
            Instant capturedAt, MultipartFile file) {
        return upload(missionId, deviceId, capturedAt, file, MEDIA_TYPE_IMAGE);
    }

    @Transactional
    @Override
    public MediaAsset upload(String missionId, String deviceId,
            Instant capturedAt, MultipartFile file, String requestedMediaType) {
        String mediaType = validate(file, requestedMediaType);
        validateRequired("missionId", missionId);
        validateRequired("deviceId", deviceId);
        MissionDeviceAssignment deviceAssignment = requireDeviceAssignment(missionId, deviceId);
        String resolvedMissionId = deviceAssignment.getMission().getId();
        String resolvedDeviceId = deviceAssignment.getDevice().getId();

        String originalFileName = safeFileName(file.getOriginalFilename());
        String contentType = file.getContentType();

        if (!useS3Storage()) {
            return saveLocal(
                    deviceAssignment,
                    resolvedMissionId,
                    resolvedDeviceId,
                    capturedAt,
                    file,
                    originalFileName,
                    contentType,
                    mediaType);
        }

        String bucket = objectStorage.bucket();
        if (bucket == null || bucket.isBlank()) {
            log.warn("AWS S3 bucket is not configured; storing image locally");
            return saveLocal(
                    deviceAssignment,
                    resolvedMissionId,
                    resolvedDeviceId,
                    capturedAt,
                    file,
                    originalFileName,
                    contentType,
                    mediaType);
        }

        String key = buildS3Key(resolvedMissionId, resolvedDeviceId, mediaType);
        String diagnosticPrefix = MEDIA_TYPE_VIDEO.equals(mediaType)
                ? "[S3-VIDEO]"
                : "[S3-IMAGE]";
        StoredObject storedObject;

        try {
            storedObject = objectStorage.put(
                    key,
                    contentType,
                    file.getSize(),
                    file.getInputStream(),
                    diagnosticPrefix);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read uploaded image");
        } catch (RuntimeException exception) {
            log.error("Cannot upload image to S3. bucket={}, key={}", bucket, key, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot upload media to S3: " + rootMessage(exception));
        }

        try {
            MediaAsset image = new MediaAsset();
            image.setDeviceAssignment(deviceAssignment);
            image.setMission(deviceAssignment.getMission());
            image.setType(mediaType);
            image.setStorageProvider(STORAGE_PROVIDER_S3);
            image.setOriginalFileName(originalFileName);
            image.setContentType(contentType);
            image.setFileSize(file.getSize());
            image.setS3Bucket(storedObject.bucket());
            image.setS3Key(storedObject.key());
            image.setS3Url(storedObject.url());
            image.setCapturedAt(capturedAt == null ? Instant.now() : capturedAt);

            return mediaAssetRepository.save(image);
        } catch (RuntimeException exception) {
            cleanupUploadedObject(storedObject.bucket(), storedObject.key());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    @Override
    public MediaAsset getById(String mediaId) {
        return mediaAssetRepository.findById(mediaId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Media not found"));
    }

    @Transactional(readOnly = true)
    @Override
    public List<MediaAsset> listByMission(String missionId, String requestedMediaType) {
        validateRequired("missionId", missionId);
        if (requestedMediaType == null || requestedMediaType.isBlank()) {
            return mediaAssetRepository.findByMissionIdOrderByCapturedAtDesc(missionId);
        }

        String mediaType = normalizeMediaType(requestedMediaType);
        return mediaAssetRepository
                .findByMissionIdAndTypeOrderByCapturedAtDesc(missionId, mediaType);
    }

    @Transactional(readOnly = true)
    @Override
    public MediaAsset getByDeviceAndId(String deviceId, String mediaId) {
        validateRequired("deviceId", deviceId);
        MediaAsset mediaAsset = getById(mediaId);
        if (!deviceId.equals(mediaAsset.getDeviceId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Media not found for device");
        }
        return mediaAsset;
    }

    @Transactional(readOnly = true)
    @Override
    public List<MediaAsset> listByDevice(String deviceId, String requestedMediaType) {
        validateRequired("deviceId", deviceId);
        if (requestedMediaType == null || requestedMediaType.isBlank()) {
            return mediaAssetRepository.findByDeviceIdOrderByCapturedAtDesc(deviceId);
        }

        String mediaType = normalizeMediaType(requestedMediaType);
        return mediaAssetRepository
                .findByDeviceIdAndTypeOrderByCapturedAtDesc(deviceId, mediaType);
    }

    @Transactional
    @Override
    public void deleteByDeviceAndId(String deviceId, String mediaId) {
        missionLock.lock(mediaId);
        MediaAsset mediaAsset = getByDeviceAndId(deviceId, mediaId);
        missionLock.requireDeletable(mediaId);
        mediaAssetRepository.delete(mediaAsset);
        mediaAssetRepository.flush();
        // FK failure or rollback must never remove the stored object.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { deleteStoredObject(mediaAsset); }
                        catch (RuntimeException exception) { log.error("Storage cleanup needs retry for deleted media {}", mediaId, exception); }
                    }
                });
    }

    @Override
    public MediaContent openMedia(MediaAsset image) {
        if (STORAGE_PROVIDER_LOCAL.equalsIgnoreCase(image.getStorageProvider())) {
            Path path = Path.of(image.getS3Url());
            try {
                return new MediaContent(
                        Files.newInputStream(path),
                        Files.size(path),
                        image.getContentType(),
                        image.getOriginalFileName());
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Cannot read local media file");
            }
        }

        try {
            StoredObjectStream stream = objectStorage
                    .open(image.getS3Bucket(), image.getS3Key());
            return new MediaContent(
                    stream.inputStream(),
                    stream.contentLength() == null ? image.getFileSize() : stream.contentLength(),
                    stream.contentType() == null || stream.contentType().isBlank()
                            ? image.getContentType()
                            : stream.contentType(),
                    image.getOriginalFileName());
        } catch (RuntimeException exception) {
            log.error("Cannot read media from S3. bucket={}, key={}",
                    image.getS3Bucket(), image.getS3Key(), exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot read media from S3: " + rootMessage(exception));
        }
    }

    @Override
    public String createPresignedGetUrl(MediaAsset image) {
        retentionAccess.requireAccessible(image);
        if (STORAGE_PROVIDER_LOCAL.equalsIgnoreCase(image.getStorageProvider())) {
            if (!migrateLocalToS3(image)) {
                return image.getS3Url();
            }
        }

        return objectStorage.createPresignedGetUrl(image.getS3Bucket(), image.getS3Key());
    }

    @Override
    public long presignedUrlExpiresSeconds() {
        return objectStorage.presignedUrlExpiresSeconds();
    }

    private String validate(MultipartFile file, String requestedMediaType) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Media file is required");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only PNG, JPEG, and MP4 media are allowed");
        }

        String mediaType = contentType.equals("video/mp4") ? MEDIA_TYPE_VIDEO : MEDIA_TYPE_IMAGE;
        if (requestedMediaType != null && !requestedMediaType.isBlank()) {
            String normalized = requestedMediaType.strip().toUpperCase();
            if (!normalized.equals(mediaType)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "mediaType does not match uploaded content type");
            }
        }

        return mediaType;
    }

    private String buildS3Key(String missionId, String deviceId, String mediaType) {
        String prefix = objectStorage.prefix();
        String normalizedPrefix = prefix == null ? "" : prefix.strip().replaceAll("^/+|/+$", "");
        String timestamp = Instant.now().toString().replaceAll("[^0-9A-Za-z]", "");
        boolean video = MEDIA_TYPE_VIDEO.equals(mediaType);
        String fileName = timestamp + "-" + UUID.randomUUID() + (video ? ".mp4" : ".jpg");
        String missionPath = safePathSegment(missionId);
        String devicePath = safePathSegment(deviceId);
        String folder = video ? "videos" : "images";
        String key = "missions/" + missionPath + "/devices/" + devicePath + "/" + folder + "/" + fileName;

        if (normalizedPrefix.isBlank()) {
            return key;
        }

        return normalizedPrefix + "/" + key;
    }

    private String safeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "gazebo-screenshot.png";
        }

        return originalFileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safePathSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void validateRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, field + " is required");
        }
    }

    private void cleanupUploadedObject(String bucket, String key) {
        objectStorage.deleteQuietly(bucket, key);
    }

    private void deleteStoredObject(MediaAsset mediaAsset) {
        if (STORAGE_PROVIDER_LOCAL.equalsIgnoreCase(mediaAsset.getStorageProvider())) {
            try {
                Files.deleteIfExists(Path.of(mediaAsset.getS3Url()));
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot delete local media file");
            }
            return;
        }

        try {
            objectStorage.delete(mediaAsset.getS3Bucket(), mediaAsset.getS3Key());
        } catch (RuntimeException exception) {
            log.error("Cannot delete media from S3. bucket={}, key={}",
                    mediaAsset.getS3Bucket(),
                    mediaAsset.getS3Key(),
                    exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot delete media from S3: " + rootMessage(exception));
        }
    }

    private MediaAsset saveLocal(
            MissionDeviceAssignment deviceAssignment,
            String missionId,
            String deviceId,
            Instant capturedAt,
            MultipartFile file,
            String originalFileName,
            String contentType,
            String mediaType) {
        String timestamp = Instant.now().toString().replaceAll("[^0-9A-Za-z]", "");
        boolean video = MEDIA_TYPE_VIDEO.equals(mediaType);
        String fileName = timestamp + "-" + UUID.randomUUID() + (video ? ".mp4" : ".jpg");
        String folder = video ? "device-videos" : "device-images";
        Path relativePath = LOCAL_IMAGE_DIR
                .getParent()
                .resolve(folder)
                .resolve(safePathSegment(missionId))
                .resolve(safePathSegment(deviceId))
                .resolve(fileName);

        try {
            Files.createDirectories(relativePath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, relativePath);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot store media locally after S3 upload failed");
        }

        MediaAsset image = new MediaAsset();
        image.setDeviceAssignment(deviceAssignment);
        image.setMission(deviceAssignment.getMission());
        image.setType(mediaType);
        image.setStorageProvider(STORAGE_PROVIDER_LOCAL);
        image.setOriginalFileName(originalFileName);
        image.setContentType(contentType);
        image.setFileSize(file.getSize());
        image.setS3Bucket(STORAGE_PROVIDER_LOCAL);
        image.setS3Key(relativePath.toString().replace('\\', '/'));
        image.setS3Url(relativePath.toAbsolutePath().toString());
        image.setCapturedAt(capturedAt == null ? Instant.now() : capturedAt);

        return mediaAssetRepository.save(image);
    }

    /**
     * Repairs media that was stored on the backend disk while S3 is now the configured storage:
     * uploads the local file under a deterministic key and flips the record to S3. Best effort.
     */
    private boolean migrateLocalToS3(MediaAsset image) {
        try {
            if (!useS3Storage() || objectStorage.bucket() == null || objectStorage.bucket().isBlank()) {
                return false;
            }
            Path localFile = Path.of(image.getS3Key());
            if (!Files.isRegularFile(localFile)) {
                return false;
            }
            String prefix = objectStorage.prefix();
            String normalizedPrefix = prefix == null ? "" : prefix.strip().replaceAll("^/+|/+$", "");
            String key = (normalizedPrefix.isBlank() ? "" : normalizedPrefix + "/")
                    + "missions/" + safePathSegment(image.getMissionId()) + "/migrated/" + image.getId() + ".jpg";
            StoredObject stored;
            try (InputStream in = Files.newInputStream(localFile)) {
                stored = objectStorage.put(key, image.getContentType(), Files.size(localFile), in, "[S3-MIGRATE]");
            }
            image.setStorageProvider(STORAGE_PROVIDER_S3);
            image.setS3Bucket(stored.bucket());
            image.setS3Key(stored.key());
            image.setS3Url(stored.url());
            mediaAssetRepository.save(image);
            return true;
        } catch (IOException | RuntimeException exception) {
            log.warn("Cannot migrate local media {} to S3: {}", image.getId(), rootMessage(exception));
            return false;
        }
    }

    private boolean useS3Storage() {
        String storage = environment.getProperty("device_IMAGE_STORAGE", "local");
        if (STORAGE_PROVIDER_LOCAL.equalsIgnoreCase(storage)) {
            // .env uses DRONE_IMAGE_STORAGE; fall back to it when the legacy key is not set.
            String configured = environment.getProperty("DRONE_IMAGE_STORAGE");
            if (configured != null && !configured.isBlank()) {
                storage = configured;
            }
        }
        return STORAGE_PROVIDER_S3.equalsIgnoreCase(storage);
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }

        return message;
    }

    private MissionDeviceAssignment requireDeviceAssignment(String missionId, String deviceId) {
        Device device = deviceService.getEntityById(deviceId);
        missionAccess.requireUploadPermission(missionId);
        return missionAccess.requireDeviceAssignment(missionId, device.getId());
    }

    private String normalizeMediaType(String requestedMediaType) {
        String mediaType = requestedMediaType.strip().toUpperCase();
        if (!MEDIA_TYPE_IMAGE.equals(mediaType) && !MEDIA_TYPE_VIDEO.equals(mediaType)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "mediaType must be IMAGE or VIDEO");
        }
        return mediaType;
    }

}
