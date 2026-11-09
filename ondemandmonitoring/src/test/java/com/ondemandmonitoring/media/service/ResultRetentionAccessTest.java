package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.ResultRetentionAccessServiceImpl;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultRetentionAccessTest {
    final Instant now = Instant.parse("2026-11-05T00:00:00Z");
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final ResultRetentionAccessServiceImpl service = new ResultRetentionAccessServiceImpl(
            media, Clock.fixed(now, ZoneOffset.UTC));
    final MediaAsset asset = new MediaAsset();

    ResultRetentionAccessTest() {
        asset.setId("image");
        asset.setStorageStatus(MediaStorageStatus.AVAILABLE);
    }

    @Test void previewBeforeHandoverHasNoRetentionDeadline() {
        when(media.findResultExpiresAtByMediaId("image")).thenReturn(Optional.empty());
        assertThatCode(() -> service.requireAccessible(asset)).doesNotThrowAnyException();
    }

    @Test void deliveredMediaIsAccessibleUntilItsDeadline() {
        when(media.findResultExpiresAtByMediaId("image")).thenReturn(Optional.of(now.plusSeconds(1)));
        assertThatCode(() -> service.requireAccessible(asset)).doesNotThrowAnyException();
    }

    @Test void retentionExpiresExactlyAtTheDeadline() {
        when(media.findResultExpiresAtByMediaId("image")).thenReturn(Optional.of(now));
        assertThatThrownBy(() -> service.requireAccessible(asset)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESULT_RETENTION_EXPIRED);
    }

    @Test void expiredMediaCannotBeReadEvenIfTheObjectStillExists() {
        when(media.findResultExpiresAtByMediaId("image")).thenReturn(Optional.of(now.minusSeconds(1)));
        assertThatThrownBy(() -> service.requireAccessible(asset)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESULT_RETENTION_EXPIRED);
    }

    @Test void unavailableStorageCannotBeReadBeforeHandoverEither() {
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        assertThatThrownBy(() -> service.requireAccessible(asset)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MEDIA_NOT_FOUND);
    }
}
