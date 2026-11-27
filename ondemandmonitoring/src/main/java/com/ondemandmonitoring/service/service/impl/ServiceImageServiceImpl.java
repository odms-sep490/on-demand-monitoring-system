package com.ondemandmonitoring.service.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.cleanup.ObjectCleanupOutbox;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class ServiceImageServiceImpl implements IServiceImageService {
    private final ServiceRepository repository;
    private final ServiceMapper mapper;
    private final IMediaObjectStorage storage;
    private final ServiceImageValidator validator;
    private final ObjectCleanupOutbox cleanupOutbox;
    private final PlatformTransactionManager transactionManager;

    @Override
    public ServiceResponse upload(String serviceId, MultipartFile file) {
        String contentType = validator.validate(file);
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String key = "service-illustrations/" + serviceId + "/" + UUID.randomUUID() + extension;
        String bucket = storage.bucket();
        String reservation = cleanupOutbox.reserveUpload(bucket, key, ServiceImageCleanupGuard.TYPE, serviceId);
        // Reserve without opening an owner transaction for normal controller calls.
        // REQUIRED joins an existing root transaction for callers that already have one.
        return new TransactionTemplate(transactionManager).execute(status -> {
            Service service = locked(serviceId);
            scheduleDeletion(service);
            try (var input = file.getInputStream()) {
                storage.put(key, contentType, file.getSize(), input, "service-illustration");
            } catch (IOException | RuntimeException exception) {
                log.warn("Service illustration upload failed. serviceId={}", serviceId, exception);
                throw new ApiException(ErrorCode.SERVICE_IMAGE_STORAGE_ERROR);
            }
            service.setImageS3Key(key);
            service.setImageS3Bucket(bucket);
            Service saved = repository.saveAndFlush(service);
            cleanupOutbox.cancelReservation(reservation);
            return response(saved);
        });
    }

    @Override
    @Transactional
    public ServiceResponse remove(String serviceId) {
        Service service = locked(serviceId);
        scheduleDeletion(service);
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

    @Override
    public void scheduleDeletion(Service service) {
        if (service.getImageS3Key() != null) {
            cleanupOutbox.enqueue(service.getImageS3Bucket(), service.getImageS3Key(),
                    ServiceImageCleanupGuard.TYPE, service.getId());
        }
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

}
