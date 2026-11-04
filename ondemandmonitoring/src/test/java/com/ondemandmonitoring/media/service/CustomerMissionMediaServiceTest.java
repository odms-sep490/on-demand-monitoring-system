package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.CustomerMediaServiceImpl;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerMissionMediaServiceTest {
    final IMissionMediaAccessService access = mock(IMissionMediaAccessService.class);
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final ICustomerMediaService service = new CustomerMediaServiceImpl(access, media, storage,
            org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class),
            mock(IResultRetentionAccessService.class));

    @BeforeEach
    void setup() {
        when(access.authorizeCustomer("MS-1")).thenReturn("mission");
        var delivery = mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "deliveryWorkflow", delivery);
        when(delivery.publishedMediaAssetIds(List.of("mission"))).thenReturn(java.util.Set.of("media", "second"));
        when(delivery.originalAccessibleMissionIds(List.of("mission"))).thenReturn(List.of("mission"));
    }

    @Test
    void pagesOnlyAvailableMediaUsingCanonicalMissionId() {
        when(media.findByMissionIdAndIdInAndMediaStatus(eq("mission"), eq(java.util.Set.of("media", "second")), eq(MediaStatus.AVAILABLE), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(asset("mission", MediaStatus.AVAILABLE)),
                        call.getArgument(3), 1));
        when(storage.createPresignedGetUrl("bucket", "final/key")).thenReturn("signed");
        var response = service.listAvailablePage("MS-1", 0, 12);
        assertThat(response.getItems().getFirst().getDownloadUrl()).isEqualTo("signed");
        verify(media, never()).save(any());
    }

    @Test
    void preventsCrossMissionOrUnvalidatedFileAccess() {
        for (MediaAsset asset : List.of(asset("other", MediaStatus.AVAILABLE),
                asset("mission", MediaStatus.VALIDATING), asset("mission", MediaStatus.REJECTED))) {
            when(media.findById("media")).thenReturn(Optional.of(asset));
            assertThatThrownBy(() -> service.getAvailableInMission("MS-1", "media"))
                    .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void reportsProcessingSeparatelyWithoutPublishingFiles() {
        when(media.countByMissionIdAndMediaStatus("mission", MediaStatus.AVAILABLE)).thenReturn(2L);
        when(media.countByMissionIdAndMediaStatusIn(eq("mission"), anyList())).thenReturn(3L);
        when(media.countByMissionIdAndMediaStatus("mission", MediaStatus.REJECTED)).thenReturn(1L);
        var status = service.getMissionMediaStatus("MS-1");
        assertThat(status.getAvailableCount()).isEqualTo(2);
        assertThat(status.getProcessingCount()).isEqualTo(3);
        assertThat(status.getRejectedCount()).isEqualTo(1);
        verifyNoInteractions(storage);
        verify(media, never()).save(any());
    }

    @Test
    void authorizationFailureDoesNotReadMediaOrSignUrls() {
        when(access.authorizeCustomer("other")).thenThrow(new ApiException(
                com.ondemandmonitoring.common.exception.ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> service.listAvailablePage("other", 0, 12)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.getMissionMediaStatus("other")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.getAvailableInMission("other", "media")).isInstanceOf(ApiException.class);
        verifyNoInteractions(media, storage);
    }

    MediaAsset asset(String missionId, MediaStatus status) {
        MediaAsset asset = new MediaAsset();
        asset.setId("media");
        asset.setMissionId(missionId);
        asset.setMediaStatus(status);
        asset.setFileSize(42L);
        asset.setS3Bucket("bucket");
        asset.setS3Key("final/key");
        return asset;
    }
}
