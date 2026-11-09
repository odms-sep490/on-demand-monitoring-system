package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.MediaUploadAttempt;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository;
import com.ondemandmonitoring.media.service.impl.MediaUploadReconciliationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.util.List;
import static org.mockito.Mockito.*;

class MediaUploadReconciliationServiceTest {
    @Test
    void validatesExistingObjectsWithoutSqsAndIsolatesFailures() {
        var attempts = mock(MediaUploadAttemptRepository.class);
        var validation = mock(IMediaValidationService.class);
        var storage = mock(IMediaObjectStorage.class);
        var missing = attempt("missing", "staging/missing");
        var ready = attempt("ready", "staging/ready");
        when(attempts.findByStatusOrderByUpdatedAtAsc(eq(UploadAttemptStatus.UPLOADED), any(Pageable.class)))
                .thenReturn(List.of(missing, ready));
        when(storage.bucket()).thenReturn("bucket");
        when(storage.inspect("bucket", "staging/missing"))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        new MediaUploadReconciliationServiceImpl(attempts, validation, storage)
                .reconcileUploadedObjects();

        verify(validation).processObjectCreated("bucket", "staging/ready", "reconciliation:ready");
        verify(validation, never()).processObjectCreated(eq("bucket"), eq("staging/missing"), anyString());
    }

    private MediaUploadAttempt attempt(String id, String key) {
        var attempt = new MediaUploadAttempt();
        attempt.setId(id);
        attempt.setStorageKey(key);
        return attempt;
    }
}
