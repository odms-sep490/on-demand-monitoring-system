package com.ondemandmonitoring.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.mapper.ChecklistEvidenceMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;

class ChecklistEvidencePosterTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void videoCandidateUsesImagePosterAndNeverVideoUrlAsThumbnail(boolean hasPoster) {
        var missions = mock(MissionRepository.class);
        var links = mock(MissionChecklistEvidenceRepository.class);
        var media = mock(MediaAssetRepository.class);
        var auth = mock(IMissionAuthorizationService.class);
        var derivatives = mock(MediaDerivativeService.class);
        var mission = new Mission(); mission.setId("m");
        var asset = new MediaAsset(); asset.setId("video"); asset.setType("VIDEO");
        asset.setValidatedAt(Instant.now()); asset.setMediaStatus(MediaStatus.AVAILABLE);
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(auth.canViewMission("m")).thenReturn(true);
        when(links.findActiveByMissionId("m")).thenReturn(List.of());
        when(media.findMissionCandidates(eq("m"), any())).thenReturn(new PageImpl<>(List.of(asset)));
        when(derivatives.previewUrl(asset)).thenReturn("https://example.test/preview.mp4");
        when(derivatives.thumbnailUrl(asset)).thenReturn(hasPoster ? "https://example.test/poster.jpg" : null);
        var service = new ChecklistEvidenceServiceImpl(missions, mock(MissionChecklistExecutionRepository.class), links,
                media, mock(MissionResultRepository.class), auth, mock(AuthenticatedUserResolver.class),
                mock(ChecklistEvidencePolicy.class), mock(ChecklistEvidenceMapper.class), mock(IMediaObjectStorage.class),
                derivatives, mock(com.ondemandmonitoring.order.repository.OrderRepository.class),
                mock(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class));
        var result = service.candidates("m", 0, 10).getFirst();
        assertThat(result.previewUrl()).endsWith(".mp4");
        assertThat(result.thumbnailUrl()).isEqualTo(hasPoster ? "https://example.test/poster.jpg" : null);
    }
}
