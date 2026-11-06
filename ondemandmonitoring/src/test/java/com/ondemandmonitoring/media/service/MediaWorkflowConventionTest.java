package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.enums.ManualUploadTaskStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MediaWorkflowConventionTest {
    private final MediaUploadPolicy policy = new MediaUploadPolicy();

    @Test
    void retryBoundaryAndManualFailureUseOnePolicy() {
        // Escalation requires STRICTLY MORE THAN three failed automatic attempts.
        assertThat(policy.failureStatus(false, 1)).isEqualTo(MediaStatus.RETRY_REQUIRED);
        assertThat(policy.failureStatus(false, 2)).isEqualTo(MediaStatus.RETRY_REQUIRED);
        assertThat(policy.failureStatus(false, 3)).isEqualTo(MediaStatus.RETRY_REQUIRED);
        assertThat(policy.failureStatus(false, 4)).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        // A manual attempt escalates immediately regardless of the automatic tally.
        assertThat(policy.failureStatus(true, 1)).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(policy.failureStatus(true, 0)).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(policy.failedAutomaticAttemptsExhausted(3)).isFalse();
        assertThat(policy.failedAutomaticAttemptsExhausted(4)).isTrue();
    }

    @Test
    void multipartBoundaryAndPartialLastPartAreConsistent() {
        assertThat(policy.useMultipart(16 * 1024 * 1024L - 1)).isFalse();
        assertThat(policy.useMultipart(16 * 1024 * 1024L)).isTrue();
        assertThat(policy.partCount(MediaUploadPolicy.PART_SIZE_BYTES * 2 + 1)).isEqualTo(3);
    }

    @Test
    void buildersPreserveManualTaskDefaults() {
        assertThat(ManualUploadTask.builder().build().getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);

    }

    @Test
    void mapperPreservesWireStatusAndExplicitUrlExpiration() {
        var mapper = Mappers.getMapper(MediaWorkflowMapper.class);
        var asset = new MediaAsset();
        asset.setMediaStatus(MediaStatus.UPLOAD_PENDING);
        asset.setId("media-1");
        var attempt = MediaUploadAttempt.builder().attemptNumber(2).build();
        attempt.setId("attempt-2");
        Instant expiry = Instant.parse("2026-09-26T01:00:00Z");
        var response = mapper.toUploadResponse(asset, attempt, "PUT", "https://upload.test",
                Map.of(), 0, 0, expiry, null);
        assertThat(response.getMediaId()).isEqualTo("media-1");
        assertThat(response.getAttemptNumber()).isEqualTo(2);
        assertThat(response.getExpiresAt()).isEqualTo(expiry);
        assertThat(mapper.toManualResponse(ManualUploadTask.builder().media(asset).build()).getStatus())
                .isEqualTo("UPLOAD_PENDING");
    }

    @Test
    void mapperRetainsLegacyAvailableStatusWithNoAttempt() {
        var asset = new MediaAsset();
        asset.setId("legacy-media");
        var response = Mappers.getMapper(MediaWorkflowMapper.class).toUploadResponse(
                asset, null, null, null, Map.of(), 0, 0, null, null);
        assertThat(response.getStatus()).isEqualTo(MediaStatus.AVAILABLE);
        assertThat(response.getAttemptNumber()).isZero();
    }
}

