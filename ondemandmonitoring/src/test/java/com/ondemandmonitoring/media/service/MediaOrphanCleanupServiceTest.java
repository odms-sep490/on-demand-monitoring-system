package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.MediaOrphanCleanupServiceImpl;
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
    final PlatformTransactionManager tx = new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    };
    final MediaOrphanCleanupServiceImpl service = new MediaOrphanCleanupServiceImpl(media, attempts, guard, storage, tx);
    MediaAsset asset;
    @BeforeEach void setup() {
        asset = new MediaAsset(); asset.setId("media"); asset.setS3Bucket("qa-only"); asset.setS3Key("canonical/media.jpg");
        asset.setMediaStatus(MediaStatus.PENDING_MANAGER_APPROVAL); asset.setValidatedAt(Instant.now());
        asset.setOrphanCleanupRequestedAt(Instant.now().minusSeconds(1000));
        when(media.findByIdForUpdate("media")).thenReturn(Optional.of(asset));
        when(media.findOrphanCleanupCandidates(any(), any())).thenReturn(List.of("media"));
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "delaySeconds", 0L);
    }
    @Test void unreferencedDraftDeletesCanonicalAndKnownStagingKeysWithoutDeletingMetadata() {
        var attempt = new MediaUploadAttempt(); attempt.setStorageKey("staging/media.jpg"); attempt.setStatus(UploadAttemptStatus.SUCCEEDED);
        when(attempts.findByMediaIdOrderByAttemptNumberDesc("media")).thenReturn(List.of(attempt));
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage).delete("qa-only", "canonical/media.jpg"); verify(storage).delete("qa-only", "staging/media.jpg");
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
        assertThat(asset.getValidatedAt()).isNull(); verify(media, never()).delete(any());
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
    @Test void transientStorageFailureIsPersistedAndRetried() {
        doThrow(new IllegalStateException("offline")).doNothing().when(storage).delete("qa-only", "canonical/media.jpg");
        assertThat(service.cleanup()).isZero(); assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
        assertThat(service.cleanup()).isEqualTo(1); assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
    }
    @Test void restartRecoversAnAbandonedClaimButSkipsAnActiveClaim() {
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING); asset.setOrphanCleanupClaimedAt(Instant.now());
        assertThat(service.cleanup()).isZero(); verifyNoInteractions(storage);
        asset.setOrphanCleanupClaimedAt(Instant.now().minusSeconds(1000));
        assertThat(service.cleanup()).isEqualTo(1);
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
}
