package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.infrastructure.s3.ResultRetentionProperties;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.ResultRetentionCleanupServiceImpl;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ResultRetentionCleanupTest {
    @Test void rechecksProtectionAfterLockingOrderBeforeMedia() {
        var media = mock(MediaAssetRepository.class);
        var orders = mock(OrderRepository.class);
        var holds = mock(ResultRevisionMediaHoldRepository.class);
        var storage = mock(IMediaObjectStorage.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var order = new Order(); order.setId("order"); order.setResultExpiresAt(Instant.now().minusSeconds(3600));
        var mission = new Mission(); mission.setOrder(order);
        var asset = new MediaAsset(); asset.setId("image"); asset.setMission(mission);
        asset.setStorageStatus(MediaStorageStatus.AVAILABLE);
        when(media.findExpiredStorageCandidateIds(any(), anyList(), any())).thenReturn(new PageImpl<>(List.of("image")));
        when(media.findOrderIdByMediaId("image")).thenReturn(Optional.of("order"));
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(media.findByIdForUpdate("image")).thenReturn(Optional.of(asset));
        when(holds.hasActiveHold(eq("image"), any())).thenReturn(true);
        var service = new ResultRetentionCleanupServiceImpl(media, orders, holds, storage,
                new ResultRetentionProperties(), transactions);
        service.cleanupExpiredResults();
        var locks = inOrder(orders, media, holds);
        locks.verify(orders).findByIdForUpdate("order");
        locks.verify(media).findByIdForUpdate("image");
        locks.verify(holds).hasActiveHold(eq("image"), any());
        verifyNoInteractions(storage);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.AVAILABLE);
    }
}
