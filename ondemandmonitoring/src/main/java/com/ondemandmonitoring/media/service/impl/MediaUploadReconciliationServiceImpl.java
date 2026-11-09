package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository;
import com.ondemandmonitoring.media.service.IMediaUploadReconciliationService;
import com.ondemandmonitoring.media.service.IMediaValidationService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaUploadReconciliationServiceImpl implements IMediaUploadReconciliationService {
    MediaUploadAttemptRepository attempts;
    IMediaValidationService validation;
    IMediaObjectStorage storage;

    @Override
    public void reconcileUploadedObjects() {
        // Bounded work; each validation runs through its own transactional service proxy.
        var candidates = attempts.findByStatusOrderByUpdatedAtAsc(
                UploadAttemptStatus.UPLOADED, PageRequest.of(0, 25));
        for (var attempt : candidates) {
            try {
                storage.inspect(storage.bucket(), attempt.getStorageKey());
                validation.processObjectCreated(storage.bucket(), attempt.getStorageKey(),
                        "reconciliation:" + attempt.getId());
            } catch (S3Exception exception) {
                // Never re-upload on access denied, network errors or eventual notification delay.
                log.warn("Cannot reconcile upload attempt={} S3 status={}",
                        attempt.getId(), exception.statusCode());
            } catch (RuntimeException exception) {
                log.warn("Cannot reconcile upload attempt={}", attempt.getId(), exception);
            }
        }
    }
}
