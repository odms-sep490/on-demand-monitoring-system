package com.ondemandmonitoring.service.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import com.ondemandmonitoring.service.mapper.ServiceMapper;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.service.service.IServiceImageService;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class ServiceImageServiceImpl implements IServiceImageService {
    private final ServiceRepository repository;
    private final ServiceMapper mapper;
    private final IMediaObjectStorage storage;
    private final ServiceImageValidator validator;

    @Override
    @Transactional
    public ServiceResponse upload(String serviceId, MultipartFile file) {
        String contentType = validator.validate(file);
        Service service = locked(serviceId);
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String key = "service-illustrations/" + serviceId + "/" + UUID.randomUUID() + extension;
        String bucket = storage.bucket();
        scheduleCleanup(service, bucket, key);
        try (var input = file.getInputStream()) {
            storage.put(key, contentType, file.getSize(), input, "service-illustration");
        } catch (IOException | RuntimeException exception) {
            log.warn("Service illustration upload failed. serviceId={}", serviceId, exception);
            throw new ApiException(ErrorCode.SERVICE_IMAGE_STORAGE_ERROR);
        }
        service.setImageS3Key(key);
        service.setImageS3Bucket(bucket);
        return response(repository.saveAndFlush(service));
    }

    @Override
    @Transactional
    public ServiceResponse remove(String serviceId) {
        Service service = locked(serviceId);
        scheduleCleanup(service, null, null);
        service.setImageS3Key(null);
        service.setImageS3Bucket(null);
        return response(repository.saveAndFlush(service));
    }

    @Override
    public String getImageUrl(Service service) {
        if (service.getImageS3Key() != null) {
            return storage.createPresignedGetUrl(service.getImageS3Bucket(), service.getImageS3Key());
        }
        return service.getImageUrl();
    }

    private Service locked(String id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
    }

    private ServiceResponse response(Service service) {
        ServiceResponse response = mapper.toResponse(service);
        response.setImageUrl(getImageUrl(service));
        response.setFallbackImageUrl(service.getImageS3Key() == null ? null : service.getImageUrl());
        return response;
    }

    private void scheduleCleanup(Service service, String newBucket, String newKey) {
        String oldBucket = service.getImageS3Bucket();
        String oldKey = service.getImageS3Key();
        // S3 is outside the DB transaction: retain the old image until commit.
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
