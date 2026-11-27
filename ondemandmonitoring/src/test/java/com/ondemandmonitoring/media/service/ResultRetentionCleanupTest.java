package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.infrastructure.s3.ResultRetentionProperties;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.ResultRetentionCleanupServiceImpl;
import com.ondemandmonitoring.media.service.impl.MediaObjectCleanup;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ResultRetentionCleanupTest {
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final OrderRepository orders = mock(OrderRepository.class);
    final MediaObjectCleanup cleanup = mock(MediaObjectCleanup.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final Order order = new Order();
    final MediaAsset asset = new MediaAsset();
    final ResultRetentionCleanupServiceImpl service = new ResultRetentionCleanupServiceImpl(
            media, orders, cleanup, new ResultRetentionProperties(), transactions);

    ResultRetentionCleanupTest() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(cleanup.enqueue(eq("private"), anyCollection(), eq("image"))).thenReturn(List.of("task"));
        when(cleanup.completed(List.of("task"))).thenReturn(true);
        order.setId("order");
        var mission = new Mission(); mission.setOrder(order);
        asset.setId("image"); asset.setMission(mission);
        asset.setStorageStatus(MediaStorageStatus.AVAILABLE);
        asset.setS3Bucket("private"); asset.setS3Key("image.jpg");
        when(media.findExpiredStorageCandidateIds(any(), anyList(), any()))
                .thenReturn(new PageImpl<>(List.of("image")));
        when(media.findOrderIdByMediaId("image")).thenReturn(Optional.of("order"));
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(media.findByIdForUpdate("image")).thenReturn(Optional.of(asset));
    }

    @Test void staleCandidateWithoutHandoverDeadlineIsNeverDeleted() {
        service.cleanupExpiredResults();
        var locks = inOrder(orders, media);
        locks.verify(orders).findByIdForUpdate("order");
        locks.verify(media).findByIdForUpdate("image");
        verifyNoInteractions(cleanup);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.AVAILABLE);
    }

    @Test void unexpiredResultsAreNeverDeleted() {
        order.setResultExpiresAt(Instant.now().plusSeconds(86400));
        service.cleanupExpiredResults();
        verifyNoInteractions(cleanup);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.AVAILABLE);
    }

    @Test void expiredResultsAreDeletedFromStorageAndMetadataIsRetained() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        service.cleanupExpiredResults();
        verify(cleanup).enqueue("private", Set.of("image.jpg"), "image");
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        assertThat(asset.getDeletedFromStorageAt()).isNotNull();
        verify(media, never()).delete(any(MediaAsset.class));
    }

    @Test void outboxPendingDoesNotMarkDeletedAndSurvivesRestart() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        when(cleanup.completed(List.of("task"))).thenReturn(false, true);
        assertThat(service.cleanupExpiredResults().deleted()).isZero();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_PENDING);
        assertThat(asset.getDeletedFromStorageAt()).isNull();
        assertThat(service.cleanupExpiredResults().deleted()).isEqualTo(1);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        verify(media, times(2)).findExpiredStorageCandidateIds(any(),
                argThat(statuses -> statuses.contains(MediaStorageStatus.DELETE_PENDING)), any());
    }
    @Test void exhaustedOutboxTaskStaysFailedUntilManualRetryCompletes() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        when(cleanup.completed(List.of("task"))).thenReturn(false);
        when(cleanup.failed(List.of("task"))).thenReturn(true);
        assertThat(service.cleanupExpiredResults().failed()).isEqualTo(1);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
        assertThat(asset.getStorageDeletionError()).isEqualTo("RESULT_RETENTION_CLEANUP_FAILED");
        service.cleanupExpiredResults();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
        when(cleanup.completed(List.of("task"))).thenReturn(true);
        service.cleanupExpiredResults();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        assertThat(asset.getStorageDeletionError()).isNull();
    }
    @Test void originalAndBothDerivativesAreQueuedInTheRetirementTransaction() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        asset.setThumbnailKey("thumbnail.jpg"); asset.setPreviewKey("preview.jpg");
        when(cleanup.completed(List.of("task"))).thenReturn(false);
        service.cleanupExpiredResults();
        verify(cleanup).enqueue("private", Set.of("image.jpg", "thumbnail.jpg", "preview.jpg"), "image");
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_PENDING);
        verify(transactions).commit(any());
    }
    @Test void enqueueFailureRollsBackRetirementAndNeverAcknowledgesDeletion() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        when(cleanup.enqueue(eq("private"), anyCollection(), eq("image")))
                .thenThrow(new IllegalStateException("private storage credentials"));
        assertThat(service.cleanupExpiredResults().failed()).isEqualTo(1);
        verify(transactions).rollback(any());
        verify(cleanup, never()).completed(anyList());
        assertThat(asset.getDeletedFromStorageAt()).isNull();
    }
}
