package com.ondemandmonitoring.device.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import com.ondemandmonitoring.device.mapper.DeviceMapper;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.service.IDeviceImageService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.validation.ImageFileValidator;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceImageServiceImpl implements IDeviceImageService {
    private final DeviceRepository repository;
    private final DeviceMapper mapper;
    private final IMediaObjectStorage storage;

    @Override
    @Transactional
    public DeviceResponse upload(String deviceId, MultipartFile file) {
        String contentType = ImageFileValidator.validate(file);
        Device device = locked(deviceId);
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String key = "device-illustrations/" + deviceId + "/" + UUID.randomUUID() + extension;
        String bucket = storage.bucket();
        scheduleCleanup(device, bucket, key);
        try (var input = file.getInputStream()) {
            storage.put(key, contentType, file.getSize(), input, "device-illustration");
        } catch (IOException | RuntimeException exception) {
            log.warn("Device image upload failed. deviceId={}", deviceId, exception);
            throw new ApiException(ErrorCode.DEVICE_IMAGE_STORAGE_ERROR);
        }
        device.setImageS3Key(key);
        device.setImageS3Bucket(bucket);
        return response(repository.saveAndFlush(device));
    }

    @Override
    @Transactional
    public DeviceResponse remove(String deviceId) {
        Device device = locked(deviceId);
        scheduleCleanup(device, null, null);
        device.setImageS3Key(null);
        device.setImageS3Bucket(null);
        return response(repository.saveAndFlush(device));
    }

    private Device locked(String id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_NOT_FOUND));
    }

    private DeviceResponse response(Device device) {
        return mapper.toResponse(device);
    }

    private void scheduleCleanup(Device device, String newBucket, String newKey) {
        String oldBucket = device.getImageS3Bucket();
        String oldKey = device.getImageS3Key();
        // S3 is outside the DB transaction: preserve the old image until commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED && oldKey != null) {
                    storage.deleteQuietly(oldBucket, oldKey);
                } else if (status == STATUS_ROLLED_BACK && newKey != null) {
                    storage.deleteQuietly(newBucket, newKey);
                }
            }
        });
    }
}
