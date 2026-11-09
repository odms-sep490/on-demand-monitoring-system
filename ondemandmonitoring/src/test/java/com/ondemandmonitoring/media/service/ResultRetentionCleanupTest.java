package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.infrastructure.s3.ResultRetentionProperties;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.ResultRetentionCleanupServiceImpl;
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
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final Order order = new Order();
    final MediaAsset asset = new MediaAsset();
    final ResultRetentionCleanupServiceImpl service = new ResultRetentionCleanupServiceImpl(
            media, orders, storage, new ResultRetentionProperties(), transactions);

    ResultRetentionCleanupTest() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
        verifyNoInteractions(storage);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.AVAILABLE);
    }

    @Test void unexpiredResultsAreNeverDeleted() {
        order.setResultExpiresAt(Instant.now().plusSeconds(86400));
        service.cleanupExpiredResults();
        verifyNoInteractions(storage);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.AVAILABLE);
    }

    @Test void expiredResultsAreDeletedFromStorageAndMetadataIsRetained() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        service.cleanupExpiredResults();
        verify(storage).delete("private", "image.jpg");
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        assertThat(asset.getDeletedFromStorageAt()).isNotNull();
        verify(media, never()).delete(any(MediaAsset.class));
    }

    @Test void storageFailureIsRecordedAndCanBeRetried() {
        order.setResultExpiresAt(Instant.now().minusSeconds(60));
        doThrow(new IllegalStateException("S3 unavailable")).doNothing()
                .when(storage).delete("private", "image.jpg");
        service.cleanupExpiredResults();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_FAILED);
        assertThat(asset.getStorageDeletionError()).isEqualTo("IllegalStateException");
        service.cleanupExpiredResults();
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETED);
        verify(storage, times(2)).delete("private", "image.jpg");
    }
}
