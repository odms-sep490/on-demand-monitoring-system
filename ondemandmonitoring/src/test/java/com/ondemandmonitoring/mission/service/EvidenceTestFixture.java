package com.ondemandmonitoring.mission.service;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.mapper.ChecklistEvidenceMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import static org.mockito.Mockito.mock;
public final class EvidenceTestFixture {
    private EvidenceTestFixture() {}
    public static IChecklistEvidenceService emptyService() {
        return new ChecklistEvidenceServiceImpl(mock(MissionRepository.class), mock(MissionChecklistExecutionRepository.class),
                mock(MissionChecklistEvidenceRepository.class), mock(MediaAssetRepository.class), mock(MissionResultRepository.class),
                mock(IMissionAuthorizationService.class), mock(AuthenticatedUserResolver.class), new ChecklistEvidencePolicy(),
                org.mapstruct.factory.Mappers.getMapper(ChecklistEvidenceMapper.class), mock(IMediaObjectStorage.class), mock(com.ondemandmonitoring.media.service.impl.MediaDerivativeService.class), mock(com.ondemandmonitoring.order.repository.OrderRepository.class), mock(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class));
    }
}
