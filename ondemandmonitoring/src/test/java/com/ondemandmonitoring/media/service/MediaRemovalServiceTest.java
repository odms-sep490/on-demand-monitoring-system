package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.request.RemoveMediaRequest;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.MediaRemovalServiceImpl;
import com.ondemandmonitoring.mission.dto.response.MissionMediaContext;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Optional;
import org.junit.jupiter.api.*;

class MediaRemovalServiceTest {
    final IMissionMediaAccessService access=mock(IMissionMediaAccessService.class);
    final IMissionMediaEvidenceGuard guard=mock(IMissionMediaEvidenceGuard.class);
    final MediaAssetRepository media=mock(MediaAssetRepository.class);
    final MediaUploadAttemptRepository attempts=mock(MediaUploadAttemptRepository.class);
    final ManualUploadTaskRepository tasks=mock(ManualUploadTaskRepository.class);
    final ResultReviewRoundEvidenceRepository published=mock(ResultReviewRoundEvidenceRepository.class);
    final IMediaAuditService audit=mock(IMediaAuditService.class);
    final AuthenticatedUserResolver user=mock(AuthenticatedUserResolver.class);
    final MediaRemovalServiceImpl service=new MediaRemovalServiceImpl(access,guard,media,attempts,tasks,published,audit,user);
    final RemoveMediaRequest request=new RemoveMediaRequest(3L," Chọn nhầm ảnh ");
    MediaAsset asset;
    @BeforeEach void setup() {
        asset=new MediaAsset(); asset.setId("media"); asset.setMissionId("mission"); asset.setVersion(3L);
        asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setAvailableAt(java.time.Instant.now());
        when(access.authorizeOperator("mission")).thenReturn(new MissionMediaContext("mission",true));
        when(media.findByIdForUpdate("media")).thenReturn(Optional.of(asset));
        var attempt=new MediaUploadAttempt(); attempt.setUploadChannel(MediaUploadChannel.STAFF_FILE);
        when(attempts.findFirstByMediaIdOrderByAttemptNumberAsc("media")).thenReturn(Optional.of(attempt));
        when(user.getCurrentUserId()).thenReturn("staff");
    }
    void blocked(ErrorCode code) {
        assertThatThrownBy(() -> service.remove("mission","media",request)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo(code));
        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.AVAILABLE);
        verifyNoInteractions(audit);
    }
    @Test void removalTombstonesAndAuditsWithoutDeletingHistory() {
        var result=service.remove("mission","media",request);
        assertThat(result.removed()).isTrue(); assertThat(result.cleanupScheduled()).isTrue();
        assertThat(asset.getMediaStatus()).isEqualTo(MediaStatus.REMOVED);
        assertThat(asset.getStorageStatus()).isEqualTo(MediaStorageStatus.DELETE_PENDING);
        assertThat(asset.getOrphanCleanupRequestedAt()).isNotNull(); assertThat(asset.getAvailableAt()).isNotNull();
        verify(audit).record(asset,null,"staff",MediaAuditAction.REMOVED,"Chọn nhầm ảnh");
        verify(media,never()).delete(any()); verify(attempts,never()).delete(any());
        var order=inOrder(guard,access,media); order.verify(access).authorizeOperator("mission");
        order.verify(guard).lockMission("mission"); order.verify(access).requireUploadPermission("mission"); order.verify(media).findByIdForUpdate("media");
    }
    @Test void replayIsIdempotentEvenWithTheOriginalVersion() {
        service.remove("mission","media",request); var requestedAt=asset.getOrphanCleanupRequestedAt();
        service.remove("mission","media",request);
        assertThat(asset.getOrphanCleanupRequestedAt()).isEqualTo(requestedAt);
        verify(audit,times(1)).record(any(),any(),any(),any(),any());
    }
    @Test void staleVersionIsRejected() {asset.setVersion(4L);blocked(ErrorCode.CONCURRENT_UPDATE);}
    @Test void foreignMissionIsRejected() {asset.setMissionId("sibling");blocked(ErrorCode.MEDIA_NOT_FOUND);}
    @Test void deniedPermissionIsNotBypassed() {doThrow(new ApiException(ErrorCode.ACCESS_DENIED)).when(access).requireUploadPermission("mission");blocked(ErrorCode.ACCESS_DENIED);verify(media,never()).findByIdForUpdate(any());}
    @Test void controllerOriginCannotBeDeletedThroughAManualRecoveryAttempt() {var first=new MediaUploadAttempt();first.setUploadChannel(MediaUploadChannel.FLIGHT_CONTROLLER);when(attempts.findFirstByMediaIdOrderByAttemptNumberAsc("media")).thenReturn(Optional.of(first));blocked(ErrorCode.MEDIA_EVIDENCE_PROTECTED);}
    @Test void activeChecklistOrResultReferencesBlockRemoval() {when(guard.hasEvidenceOrResultReferences("media")).thenReturn(true);blocked(ErrorCode.MEDIA_EVIDENCE_PROTECTED);}
    @Test void anyPublishedRoundIncludingHistoricalRoundsBlocksRemoval() {when(published.existsByMediaAsset_Id("media")).thenReturn(true);blocked(ErrorCode.MEDIA_EVIDENCE_PROTECTED);}
    @Test void selectedPreviewBlocksRemoval() {asset.setPreviewSelected(true);blocked(ErrorCode.MEDIA_EVIDENCE_PROTECTED);}
    @Test void anOpenManualTaskIsClosedButRetained() {var task=new ManualUploadTask();task.setStatus(ManualUploadTaskStatus.OPEN);when(tasks.findByMediaId("media")).thenReturn(Optional.of(task));service.remove("mission","media",request);assertThat(task.getStatus()).isEqualTo(ManualUploadTaskStatus.RESOLVED);assertThat(task.getResolvedAt()).isNotNull();verify(tasks,never()).delete(any());}
    @Test void blankReasonIsRejectedBeforeAnyLock() {assertThatThrownBy(() -> service.remove("mission","media",new RemoveMediaRequest(3L," "))).isInstanceOf(ApiException.class);verifyNoInteractions(access,guard,audit);}
}
