package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.impl.ManagerMediaApprovalServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ManagerMediaApprovalServiceTest {

    private final MediaAssetRepository media = mock(MediaAssetRepository.class);

    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final MediaWorkflowMapper mapper = org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class);
    private final ManagerMediaApprovalServiceImpl service =
            new ManagerMediaApprovalServiceImpl(media, storage, mapper, mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));

    @Test
    void approveMakesMediaAvailableForDelivery() {
        MediaAsset asset = asset();
        when(media.findByIdForUpdate("media-1")).thenReturn(Optional.of(asset));
        when(media.save(asset)).thenReturn(asset);
        when(storage.createPresignedGetUrl("bucket", "final/key")).thenReturn("signed");
        when(storage.presignedUrlExpiresSeconds()).thenReturn(300L);

        var response = service.approve("mission-1", "media-1");

        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.AVAILABLE);
        assertThat(asset.getAvailableAt()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(MediaStatus.AVAILABLE);

    }

    private MediaAsset asset() {
        MediaAsset asset = new MediaAsset();
        asset.setId("media-1");
        asset.setMissionId("mission-1");
        asset.setMediaStatus(MediaStatus.PENDING_MANAGER_APPROVAL);
        asset.setType("IMAGE");
        asset.setFileSize(12L);
        asset.setS3Bucket("bucket");
        asset.setS3Key("final/key");
        return asset;
    }
}
