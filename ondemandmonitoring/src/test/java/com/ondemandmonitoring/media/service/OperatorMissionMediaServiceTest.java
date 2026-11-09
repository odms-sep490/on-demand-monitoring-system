package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.OperatorMissionMediaServiceImpl;
import com.ondemandmonitoring.mission.dto.response.MissionMediaContext;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatorMissionMediaServiceTest {
    final IMissionMediaAccessService access = mock(IMissionMediaAccessService.class);
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final MediaWorkflowMapper mapper = org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class);
    final IOperatorMissionMediaService service = new OperatorMissionMediaServiceImpl(access, media, storage, mapper);

    @Test
    void listsOnlyAvailableForAuthorizedCanonicalMissionWithPagination() {
        when(access.authorizeOperator("MS-1")).thenReturn(new MissionMediaContext("mission", false));
        MediaAsset asset = asset("mission", MediaStatus.AVAILABLE);
        asset.setSourceType("SATELLITE_SNAPSHOT");
        when(media.findByMissionIdAndMediaStatus(eq("mission"), eq(MediaStatus.AVAILABLE), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(asset), call.getArgument(2), 1));
        when(storage.createPresignedGetUrl("bucket", "final/key")).thenReturn("signed-url");
        when(storage.presignedUrlExpiresSeconds()).thenReturn(300L);

        var response = service.listAvailable("MS-1", 0, 12);

        assertThat(response.getTotalItems()).isEqualTo(1);
        assertThat(response.getItems().getFirst().getDownloadUrl()).isEqualTo("signed-url");
        assertThat(response.getItems().getFirst().getMediaType()).isEqualTo("IMAGE");
        assertThat(response.getItems().getFirst().getSourceType()).isEqualTo("SATELLITE_SNAPSHOT");
        assertThat(response.getItems().getFirst().getUrlExpiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void deniesUnauthorizedMissionBeforeReadingMedia() {
        when(access.authorizeOperator("mission")).thenThrow(new ApiException(
                com.ondemandmonitoring.common.exception.ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> service.listAvailable("mission", 0, 12)).isInstanceOf(ApiException.class);
        verifyNoInteractions(media, storage);
    }

    @Test
    void doesNotSignMediaFromOtherMissionOrUnvalidatedMedia() {
        when(access.authorizeOperator("mission")).thenReturn(new MissionMediaContext("mission", false));
        for (MediaAsset asset : List.of(asset("other", MediaStatus.AVAILABLE),
                asset("mission", MediaStatus.VALIDATING))) {
            when(media.findById("media")).thenReturn(Optional.of(asset));
            assertThatThrownBy(() -> service.getAvailable("mission", "media")).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void refreshesUrlForAvailableMediaOnly() {
        when(access.authorizeOperator("mission")).thenReturn(new MissionMediaContext("mission", false));
        when(media.findById("media")).thenReturn(Optional.of(asset("mission", MediaStatus.AVAILABLE)));
        when(storage.createPresignedGetUrl("bucket", "final/key")).thenReturn("fresh-url");
        assertThat(service.getAvailable("mission", "media").getDownloadUrl()).isEqualTo("fresh-url");
    }

    private MediaAsset asset(String missionId, MediaStatus status) {
        MediaAsset asset = new MediaAsset();
        asset.setId("media");
        asset.setMissionId(missionId);
        asset.setMediaStatus(status);
        asset.setType("IMAGE");
        asset.setFileSize(42L);
        asset.setS3Bucket("bucket");
        asset.setS3Key("final/key");
        return asset;
    }
}
