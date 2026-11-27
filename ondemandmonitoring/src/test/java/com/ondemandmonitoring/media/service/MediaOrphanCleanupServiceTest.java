package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.MediaOrphanCleanupServiceImpl;
import com.ondemandmonitoring.media.service.impl.MediaObjectCleanup;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaOrphanCleanupServiceTest {
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final MediaUploadAttemptRepository attempts = mock(MediaUploadAttemptRepository.class);
    final IMissionMediaEvidenceGuard guard = mock(IMissionMediaEvidenceGuard.class);
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final MediaObjectCleanup cleanup = mock(MediaObjectCleanup.class);
    final PlatformTransactionManager tx = spy(new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    });
    final MediaOrphanCleanupServiceImpl service = new MediaOrphanCleanupServiceImpl(media, attempts, guard, storage, tx, cleanup);
    MediaAsset asset;
    @BeforeEach void setup() {
        asset = new MediaAsset(); asset.setId("media"); asset.setS3Bucket("qa-only"); asset.setS3Key("canonical/media.jpg");
        asset.setMediaStatus(MediaStatus.PENDING_MANAGER_APPROVAL); asset.setValidatedAt(Instant.now());
        asset.setOrphanCleanupRequestedAt(Instant.now().minusSeconds(1000));
        when(media.findByIdForUpdate("media")).thenReturn(Optional.of(asset));
        when(media.findOrphanCleanupCandidates(any(), any())).thenReturn(List.of("media"));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of());
        when(cleanup.enqueue(eq("qa-only"), anyCollection(), eq("media"))).thenReturn(List.of("task"));
        when(cleanup.completed(List.of("task"))).thenReturn(true);
        ReflectionTestUtils.setField(service, "delaySeconds", 0L);
    }
    @Test void unreferencedDraftDeletesCanonicalAndKnownStagingKeysWithoutDeletingMetadata() {
        var attempt = new MediaUploadAttempt(); attempt.setStorageKey("staging/media.jpg"); attempt.setStatus(UploadAttemptStatus.SUCCEEDED);
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        assertThat(service.cleanup()).isEqualTo(1);
        verify(cleanup).enqueue(eq("qa-only"), argThat((Collection<String> keys) -> keys.containsAll(List.of("canonical/media.jpg", "staging/media.jpg"))), eq("media"));
        verify(storage, never()).delete(anyString(), anyString());
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
        assertThat(asset.getValidatedAt()).isNull(); verify(media, never()).delete(any());
    }
    @Test void failedMultipartIsAbortedDuringOrphanCleanup() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.FAILED);
        attempt.setStorageKey("staging/failed.mp4"); attempt.setMultipartUploadId("upload-id");
        attempt.setExpiresAt(Instant.now().minusSeconds(3600));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage).abortMultipartUpload("staging/failed.mp4", "upload-id");
    }
    @Test void aRemainingChecklistOrResultReferenceCancelsCleanup() {
        when(guard.hasEvidenceOrResultReferences("media")).thenReturn(true);
        assertThat(service.cleanup()).isZero(); verifyNoInteractions(storage);
        assertThat(asset.getOrphanCleanupRequestedAt()).isNull();
    }
    @Test void acceptedOrPreviewMediaIsRetainedEvenWithoutAnActiveChecklistLink() {
        asset.setMediaStatus(MediaStatus.AVAILABLE);
        assertThat(service.cleanup()).isZero(); verifyNoInteractions(storage);
        asset.setOrphanCleanupRequestedAt(Instant.now().minusSeconds(1000)); asset.setMediaStatus(MediaStatus.PENDING_MANAGER_APPROVAL); asset.setPreviewSelected(true);
        assertThat(service.cleanup()).isZero(); verifyNoInteractions(storage);
    }
    @Test void anUnexpiredTransferIsNotDeleted() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.PENDING); attempt.setExpiresAt(Instant.now().plusSeconds(60));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        assertThat(service.cleanup()).isZero(); verifyNoInteractions(storage);
        assertThat(asset.getOrphanCleanupRequestedAt()).isNotNull();
    }
    @Test void expiredSignedUrlStillWaitsForTransferGrace() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.SUCCEEDED);
        attempt.setExpiresAt(Instant.now().minusSeconds(30));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        assertThat(service.cleanup()).isZero();
        verifyNoInteractions(storage, cleanup);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.AVAILABLE);
    }
    @Test void derivativeKeysAreIncludedAndDuplicateKeysAreNotPublishedTwice() {
        asset.setThumbnailKey("derived/thumbnail.jpg"); asset.setPreviewKey("derived/thumbnail.jpg");
        service.cleanup();
        verify(cleanup).enqueue("qa-only", Set.of("canonical/media.jpg", "derived/thumbnail.jpg"), "media");
        verifyNoInteractions(storage);
    }
    @Test void normalOutboxRetriesKeepTheDomainPendingUntilAllKeysComplete() {
        when(cleanup.completed(List.of("task"))).thenReturn(false, true);
        assertThat(service.cleanup()).isZero();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_PENDING);
        assertThat(asset.getDeletedFromStorageAt()).isNull();
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        verifyNoInteractions(storage);
    }
    @Test void restartReconcilesAlreadyQueuedTasksWithoutWaitingForTheOldClaimLease() {
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        asset.setOrphanCleanupClaimedAt(Instant.now());
        assertThat(service.cleanup()).isEqualTo(1);
        verify(cleanup).enqueue(eq("qa-only"), anyCollection(), eq("media"));
        verifyNoInteractions(storage);
    }
    @Test void deadOutboxTaskStaysFailedUntilAnAdministratorRetriesIt() {
        when(cleanup.completed(List.of("task"))).thenReturn(false);
        when(cleanup.failed(List.of("task"))).thenReturn(true);
        assertThat(service.cleanup()).isZero();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
        assertThat(asset.getStorageDeletionError()).isEqualTo("ORPHAN_CLEANUP_FAILED");
        assertThat(service.cleanup()).isZero();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
        when(cleanup.completed(List.of("task"))).thenReturn(true);
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(asset.getStorageDeletionError()).isNull();
    }
    @Test void failedMultipartAbortNeverPublishesDeleteTasksAndCanBeRetried() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.FAILED);
        attempt.setStorageKey("staging/failed.mp4"); attempt.setMultipartUploadId("upload-id");
        attempt.setExpiresAt(Instant.now().minusSeconds(3600));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        doThrow(new IllegalStateException("secret")).doNothing().when(storage)
                .abortMultipartUpload("staging/failed.mp4", "upload-id");
        assertThat(service.cleanup()).isZero();
        verifyNoInteractions(cleanup);
        assertThat(asset.getStorageDeletionError()).isEqualTo("ORPHAN_MULTIPART_ABORT_FAILED");
        assertThat(service.cleanup()).isEqualTo(1);
        var order = inOrder(storage, cleanup);
        order.verify(storage, times(2)).abortMultipartUpload("staging/failed.mp4", "upload-id");
        order.verify(cleanup).enqueue(eq("qa-only"), anyCollection(), eq("media"));
    }
    @Test void retryAfterAbortButBeforeCommitTreatsOnlyNoSuchUploadAsAlreadyAborted() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.FAILED);
        attempt.setStorageKey("staging/failed.mp4"); attempt.setMultipartUploadId("upload-id");
        attempt.setExpiresAt(Instant.now().minusSeconds(3600));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        attempt.setFailureCode("ORPHAN_CLEANUP"); // Earlier cleanup versions recorded this before aborting.
        var missingUpload = software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("NoSuchUpload").build()).build();
        doThrow(missingUpload).when(storage).abortMultipartUpload("staging/failed.mp4", "upload-id");
        assertThat(service.cleanup()).isEqualTo(1);
        verify(cleanup).enqueue(eq("qa-only"), anyCollection(), eq("media"));
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
    }
    @Test void missingBucketAndAccessDeniedNeverPublishDeleteTasks() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.FAILED);
        attempt.setStorageKey("staging/failed.mp4"); attempt.setMultipartUploadId("upload-id");
        attempt.setExpiresAt(Instant.now().minusSeconds(3600));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        for (var code : List.of("NoSuchBucket", "AccessDenied")) {
            var failure = software.amazon.awssdk.services.s3.model.S3Exception.builder()
                    .statusCode(code.equals("AccessDenied") ? 403 : 404)
                    .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                            .errorCode(code).build()).build();
            doThrow(failure).when(storage).abortMultipartUpload("staging/failed.mp4", "upload-id");
            assertThat(service.cleanup()).isZero();
            assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
            assertThat(asset.getStorageDeletionError()).isEqualTo("ORPHAN_MULTIPART_ABORT_FAILED");
        }
        verifyNoInteractions(cleanup);
    }
    @Test void aQueuedMultipartTaskDoesNotAbortAgainOnEachReconciliation() {
        var attempt = new MediaUploadAttempt(); attempt.setStatus(UploadAttemptStatus.FAILED);
        attempt.setStorageKey("staging/failed.mp4"); attempt.setMultipartUploadId("upload-id");
        attempt.setExpiresAt(Instant.now().minusSeconds(3600));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        when(cleanup.completed(List.of("task"))).thenReturn(false, true);
        assertThat(service.cleanup()).isZero();
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage, times(1)).abortMultipartUpload("staging/failed.mp4", "upload-id");
    }
    @Test void enqueueAndRetirementParticipateInTheSameRollbackBoundary() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_PENDING);
            throw new IllegalStateException("database unavailable");
        }).when(cleanup).enqueue(eq("qa-only"), anyCollection(), eq("media"));
        assertThat(service.cleanup()).isZero();
        verifyNoInteractions(storage);
        verify(tx).rollback(any());
        verify(cleanup, never()).completed(anyList());
    }
    @Test void reattachingBeforeClaimCancelsDeletion() {
        service.cancel("media");
        assertThat(asset.getOrphanCleanupRequestedAt()).isNull(); assertThat(service.cleanup()).isZero();
        verifyNoInteractions(storage);
    }
    @Test void reattachingDuringOrAfterDeletionIsRejectedUntilAValidUploadRestoresStorage() {
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        assertThatThrownBy(() -> service.cancel("media")).isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        asset.setStorageStatus(MediaStorageStatus.DELETED);
        assertThatThrownBy(() -> service.cancel("media")).isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
    }
    @Test void detachRequestIsNotScheduledWhileTheFileIsStillUsed() {
        asset.setOrphanCleanupRequestedAt(null); when(guard.hasEvidenceOrResultReferences("media")).thenReturn(true);
        service.request("media"); assertThat(asset.getOrphanCleanupRequestedAt()).isNull();
    }
    @Test void removedAvailableMediaIsCleanedWithoutLosingTheTombstoneAndAuditTimestamps() {
        var validated=asset.getValidatedAt(); asset.setMediaStatus(MediaStatus.REMOVED);
        asset.setAvailableAt(Instant.now()); asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.REMOVED);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        assertThat(asset.getValidatedAt()).isEqualTo(validated);
        assertThat(asset.getAvailableAt()).isNotNull();
    }
    @Test void removedMediaKeepsItsTombstoneWhenDeletionFailsAndRetries() {
        asset.setMediaStatus(MediaStatus.REMOVED); asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        when(cleanup.completed(List.of("task"))).thenReturn(false, true);
        assertThat(service.cleanup()).isZero(); assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.REMOVED);
        assertThat(service.cleanup()).isEqualTo(1); assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.REMOVED);
    }

}
