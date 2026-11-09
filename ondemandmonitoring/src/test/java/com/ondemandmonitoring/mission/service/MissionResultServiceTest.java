package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MissionResultServiceTest {

    private final MissionResultRepository results = mock(MissionResultRepository.class);
    private final MissionResultMapper mapper = mock(MissionResultMapper.class);
    private final MissionResultService service = new MissionResultService(
            results,
            mock(MissionRepository.class),
            mock(MediaAssetRepository.class),
            mock(IMediaAssetService.class),
            mapper,
            mock(com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy.class),
            mock(IMissionChecklistExecutionService.class),
            mock(AuthenticatedUserResolver.class),
            mock(IMissionAuthorizationService.class)
    );

    @Test
    void missingResultReturnsNullInsteadOfThrowing() {
        when(results.findByMissionId("mission-1")).thenReturn(Optional.empty());

        assertThat(service.getByMissionId("mission-1")).isNull();

        verify(results).findByMissionId("mission-1");
        verifyNoInteractions(mapper);
    }
}
