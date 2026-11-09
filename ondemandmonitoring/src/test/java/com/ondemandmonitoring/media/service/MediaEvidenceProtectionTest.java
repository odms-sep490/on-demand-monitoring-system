package com.ondemandmonitoring.media.service;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.device.service.IDeviceService;
import org.springframework.core.env.Environment;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class MediaEvidenceProtectionTest {
    @Test void evidenceHistoryDeniesDeleteBeforeAnyStorageMutation() {
        var media=mock(MediaAssetRepository.class); var evidence=mock(MissionChecklistEvidenceRepository.class);
        var lock=new com.ondemandmonitoring.mission.service.impl.MissionMediaEvidenceGuardImpl(media,mock(MissionRepository.class),mock(MissionResultRepository.class),evidence);
        var storage=mock(IMediaObjectStorage.class); var asset=new MediaAsset(); asset.setId("a"); asset.setDeviceId("d");
        when(media.findById("a")).thenReturn(Optional.of(asset)); when(evidence.existsByMediaAsset_Id("a")).thenReturn(true);
        var service=new MediaAssetServiceImpl(storage,mock(IResultRetentionAccessService.class),mock(Environment.class),mock(IDeviceService.class),media,mock(com.ondemandmonitoring.mission.service.IMissionMediaAccessService.class),mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
        var commandLock=mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class); org.springframework.test.util.ReflectionTestUtils.setField(service,"missionLock",commandLock);
        doAnswer(call->{lock.requireDeletable("a");return null;}).when(commandLock).requireDeletable("a");
        assertEquals(ErrorCode.MEDIA_EVIDENCE_PROTECTED,assertThrows(ApiException.class,()->service.deleteByDeviceAndId("d","a")).getErrorCode());
        verifyNoInteractions(storage); verify(media,never()).delete(any());
    }
    @Test void approvedActiveEvidenceCannotBeRejected() {
        var evidence=mock(MissionChecklistEvidenceRepository.class); var results=mock(MissionResultRepository.class);
        when(evidence.existsByMediaAsset_IdAndDetachedAtIsNull("a")).thenReturn(true);
        var result=new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.APPROVED); when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        var lock=new com.ondemandmonitoring.mission.service.impl.MissionMediaEvidenceGuardImpl(mock(MediaAssetRepository.class),mock(MissionRepository.class),results,evidence);
        assertThrows(ApiException.class,()->lock.requireMutable("m","a")); result.setApprovalStatus(MissionResultApprovalStatus.REJECTED); assertDoesNotThrow(()->lock.requireMutable("m","a"));
    }
}
