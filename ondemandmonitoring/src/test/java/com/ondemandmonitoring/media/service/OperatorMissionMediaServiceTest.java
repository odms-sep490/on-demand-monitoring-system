package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository;
import com.ondemandmonitoring.media.repository.ManualUploadTaskRepository;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
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
    final MediaUploadAttemptRepository attempts = mock(MediaUploadAttemptRepository.class);
    final ManualUploadTaskRepository manualTasks = mock(ManualUploadTaskRepository.class);
    final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    final MediaDerivativeService derivatives = mock(MediaDerivativeService.class);
    final MediaWorkflowMapper mapper = org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class);
    final IResultRetentionAccessService retention = mock(IResultRetentionAccessService.class);
    final IOperatorMissionMediaService service = new OperatorMissionMediaServiceImpl(
            access, media, attempts, manualTasks, storage, derivatives, mapper, retention);

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

    @Test
    void workspaceIncludesFailedStaffAttemptWithoutSigningStagingObject() {
        when(access.authorizeOperator("MS-1")).thenReturn(new MissionMediaContext("mission", false));
        var asset = asset("mission", MediaStatus.MANUAL_UPLOAD_REQUIRED);
        asset.setLocalMediaId("staff-capture"); asset.setChecksumSha256("a".repeat(64));
        var attempt = new com.ondemandmonitoring.media.domain.MediaUploadAttempt();
        attempt.setMedia(asset); attempt.setAttemptNumber(2);
        attempt.setUploadChannel(com.ondemandmonitoring.media.enums.MediaUploadChannel.STAFF_FILE);
        attempt.setFailureCode("TRANSFER_FAILED"); attempt.setFailureMessage("offline");
        when(media.findMissionCandidates(eq("mission"), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(asset), call.getArgument(1), 1));
        when(attempts.findLatestByMediaIds(List.of("media"))).thenReturn(List.of(attempt));
        var response = service.listWorkspace("MS-1", 0, 50).getItems().getFirst();
        assertThat(response.getUploadChannel()).isEqualTo(com.ondemandmonitoring.media.enums.MediaUploadChannel.STAFF_FILE);
        assertThat(response.getAttemptNumber()).isEqualTo(2);
        assertThat(response.getFailureMessage()).isEqualTo("offline");
        assertThat(response.getDownloadUrl()).isNull();
        verifyNoInteractions(storage, derivatives);
    }

    @Test void originalInspectionAllowsValidatedPendingMediaForAssignedReviewer() {
        when(access.authorizeOperator("MS-1")).thenReturn(new MissionMediaContext("mission", false));
        var asset = asset("mission", MediaStatus.PENDING_MANAGER_APPROVAL);
        asset.setStorageProvider("S3"); asset.setValidatedAt(java.time.Instant.now());
        when(media.findById("media")).thenReturn(Optional.of(asset));
        when(storage.createPresignedGetUrl("bucket", "final/key")).thenReturn("original-url");
        assertThat(service.createOriginalView("MS-1", "media").viewUrl()).isEqualTo("original-url");
        var order = inOrder(access, retention, storage);
        order.verify(access).authorizeOperator("MS-1");
        order.verify(retention).requireAccessible(asset);
        order.verify(storage).createPresignedGetUrl("bucket", "final/key");
    }

    @Test void originalInspectionRejectsOtherMissionRemovedAndUnvalidatedMedia() {
        when(access.authorizeOperator("mission")).thenReturn(new MissionMediaContext("mission", false));
        for (var asset : List.of(asset("other", MediaStatus.AVAILABLE),
                asset("mission", MediaStatus.REMOVED), asset("mission", MediaStatus.VALIDATING))) {
            asset.setStorageProvider("S3");
            when(media.findById("media")).thenReturn(Optional.of(asset));
            assertThatThrownBy(() -> service.createOriginalView("mission", "media")).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test void originalInspectionRejectsUnauthorizedAccess() {
        when(access.authorizeOperator("mission")).thenThrow(new ApiException(
                com.ondemandmonitoring.common.exception.ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> service.createOriginalView("mission", "media")).isInstanceOf(ApiException.class);
        verifyNoInteractions(media, storage);
    }

    @Test void originalInspectionRejectsExpiredRetentionBeforeSigning() {
        when(access.authorizeOperator("mission")).thenReturn(new MissionMediaContext("mission", false));
        var asset = asset("mission", MediaStatus.AVAILABLE);
        asset.setStorageProvider("S3"); asset.setValidatedAt(java.time.Instant.now());
        when(media.findById("media")).thenReturn(Optional.of(asset));
        doThrow(new ApiException(com.ondemandmonitoring.common.exception.ErrorCode.RESULT_RETENTION_EXPIRED))
                .when(retention).requireAccessible(asset);
        assertThatThrownBy(() -> service.createOriginalView("mission", "media")).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }

    @Test void removedValidatedAndDeletedObjectsCannotBeInspected() {
        when(access.authorizeOperator("mission")).thenReturn(new MissionMediaContext("mission", false));
        var removed = asset("mission", MediaStatus.REMOVED);
        removed.setStorageProvider("S3"); removed.setValidatedAt(java.time.Instant.now());
        var deleted = asset("mission", MediaStatus.AVAILABLE);
        deleted.setStorageProvider("S3"); deleted.setValidatedAt(java.time.Instant.now());
        deleted.setStorageStatus(com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED);
        for (var asset : List.of(removed, deleted)) {
            when(media.findById("media")).thenReturn(Optional.of(asset));
            assertThatThrownBy(() -> service.createOriginalView("mission", "media")).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(storage);
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
