package com.ondemandmonitoring.media.cleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.service.IMediaObjectStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.Optional;

class ObjectCleanupWorkerTest {
    ObjectCleanupOutbox outbox;
    IMediaObjectStorage storage;
    ObjectCleanupGuard guard;
    ObjectCleanupProperties properties;
    ObjectCleanupWorker worker;
    final ObjectCleanupOutbox.Claim task =
            new ObjectCleanupOutbox.Claim("id", "token", "bucket", "key", "TEST", "owner", 1);

    @BeforeEach
    void setup() {
        outbox = mock(ObjectCleanupOutbox.class);
        storage = mock(IMediaObjectStorage.class);
        guard = mock(ObjectCleanupGuard.class);
        properties = new ObjectCleanupProperties();
        when(guard.type()).thenReturn("TEST");
        when(outbox.claimNext()).thenReturn(Optional.of(task), Optional.empty());
        when(outbox.inspect(task, guard)).thenReturn(ObjectCleanupGuard.Decision.DELETE);
        worker = new ObjectCleanupWorker(outbox, storage, properties, List.of(guard));
    }

    @Test
    void deletionIsAcknowledgedOnlyAfterStorageSucceeds() {
        assertEquals(1, worker.poll());
        var order = inOrder(storage, outbox);
        order.verify(storage).delete("bucket", "key");
        order.verify(outbox).complete(task);
        verify(outbox).purgeCompleted();
    }

    @Test
    void storageFailuresRemainRetryable() {
        var failure = new IllegalStateException("sensitive storage detail");
        doThrow(failure).when(storage).delete("bucket", "key");
        worker.poll();
        verify(outbox).fail(task, failure);
        verify(outbox, never()).complete(any());
    }

    @Test
    void missingKeyIsIdempotentSuccess() {
        doThrow(s3("NoSuchKey", 404)).when(storage).delete("bucket", "key");
        worker.poll();
        verify(outbox).complete(task);
        verify(outbox, never()).fail(any(), any());
    }

    @Test
    void missingBucketAndPermissionDeniedAreNotAcknowledged() {
        for (var error : List.of(s3("NoSuchBucket", 404), s3("AccessDenied", 403))) {
            reset(storage);
            when(outbox.claimNext()).thenReturn(Optional.of(task), Optional.empty());
            doThrow(error).when(storage).delete("bucket", "key");
            worker.poll();
            verify(outbox).fail(task, error);
        }
        verify(outbox, never()).complete(any());
    }

    @Test
    void activeReferenceAndBusyOwnerNeverDelete() {
        when(outbox.inspect(task, guard)).thenReturn(ObjectCleanupGuard.Decision.REFERENCED);
        worker.poll();
        verify(outbox).cancel(task);
        when(outbox.claimNext()).thenReturn(Optional.of(task), Optional.empty());
        when(outbox.inspect(task, guard)).thenReturn(ObjectCleanupGuard.Decision.DEFER);
        worker.poll();
        verify(outbox).defer(task);
        verifyNoInteractions(storage);
    }

    @Test
    void unknownAndMissingGuardDecisionsFailClosed() {
        new ObjectCleanupWorker(outbox, storage, properties, List.of()).poll();
        verify(outbox).fail(eq(task), isA(IllegalStateException.class));
        when(outbox.claimNext()).thenReturn(Optional.of(task), Optional.empty());
        when(outbox.inspect(task, guard)).thenReturn(null);
        worker.poll();
        verify(outbox).fail(eq(task), isA(NullPointerException.class));
        verifyNoInteractions(storage);
    }

    @Test
    void disabledWorkerLeavesDurableQueueUntouched() {
        properties.setEnabled(false);
        assertEquals(0, worker.poll());
        verifyNoInteractions(outbox, storage);
    }

    @Test
    void duplicateGuardRegistrationFailsStartup() {
        assertThrows(
                IllegalStateException.class,
                () -> new ObjectCleanupWorker(outbox, storage, properties, List.of(guard, guard)));
    }

    private S3Exception s3(String code, int status) {
        var builder = S3Exception.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }
}
