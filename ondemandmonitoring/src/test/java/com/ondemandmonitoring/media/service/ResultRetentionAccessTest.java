package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.ResultRetentionAccessServiceImpl;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultRetentionAccessTest {
    final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final ResultRevisionMediaHoldRepository holds = mock(ResultRevisionMediaHoldRepository.class);
    final ResultRetentionAccessServiceImpl service = new ResultRetentionAccessServiceImpl(
            media, Clock.fixed(now, ZoneOffset.UTC), holds);
    final MediaAsset asset = new MediaAsset();
    ResultRetentionAccessTest() {
        asset.setId("image"); asset.setStorageStatus(MediaStorageStatus.AVAILABLE);
        when(media.findResultExpiresAtByMediaId("image")).thenReturn(Optional.of(now.minusSeconds(1)));
    }
    @Test void customerDownloadStillHonorsTheRetentionDeadline() {
        assertThatThrownBy(() -> service.requireAccessible(asset)).isInstanceOf(ApiException.class);
    }
    @Test void activeCaseCanReviewAnExpiredButRetainedImage() {
        when(holds.belongsToActiveCase("image", "case")).thenReturn(true);
        assertThatCode(() -> service.requireAccessibleForRevision(asset, "case")).doesNotThrowAnyException();
    }
    @Test void anotherCaseCannotBypassRetention() {
        assertThatThrownBy(() -> service.requireAccessibleForRevision(asset, "other")).isInstanceOf(ApiException.class);
    }
    @Test void evenAnActiveCaseCannotReadAnObjectBeingDeleted() {
        when(holds.belongsToActiveCase("image", "case")).thenReturn(true);
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        assertThatThrownBy(() -> service.requireAccessibleForRevision(asset, "case")).isInstanceOf(ApiException.class);
    }
}
