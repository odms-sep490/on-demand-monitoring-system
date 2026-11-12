package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.service.IDeviceService;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.request.PrepareMediaUploadRequest;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.MediaUploadChannel;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.MediaUploadServiceImpl;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionMediaAccessServiceImpl;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class MediaUploadWorkflowTest {
    private final MissionRepository missions = mock(MissionRepository.class);
    private final MissionDeviceAssignmentRepository deviceAssignments = mock(MissionDeviceAssignmentRepository.class);
    private final MissionStaffAssignmentRepository staffAssignments = mock(MissionStaffAssignmentRepository.class);
    private final IDeviceService devices = mock(IDeviceService.class);
    private final MediaAssetRepository media = mock(MediaAssetRepository.class);
    private final MediaUploadAttemptRepository attempts = mock(MediaUploadAttemptRepository.class);
    private final ManualUploadTaskRepository manualTasks = mock(ManualUploadTaskRepository.class);
    private final MediaAuditLogRepository audit = mock(MediaAuditLogRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final AuthenticatedUserResolver userResolver = mock(AuthenticatedUserResolver.class);
    private final com.ondemandmonitoring.mission.service.IMissionAuthorizationService authorization =
            mock(com.ondemandmonitoring.mission.service.IMissionAuthorizationService.class);
    private final MissionMediaAccessServiceImpl missionAccess = new MissionMediaAccessServiceImpl(
            missions, deviceAssignments, userResolver, authorization);
    private final MediaUploadServiceImpl service = new MediaUploadServiceImpl(missionAccess, devices,
            media, attempts, manualTasks,
            new com.ondemandmonitoring.media.service.impl.ManualUploadTaskServiceImpl(manualTasks),
            audit, storage, userResolver, mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class),
            new com.ondemandmonitoring.media.policy.MediaUploadPolicy());

    @BeforeEach
    void setUp() {
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        when(authorization.canOperatePayload("mission-id")).thenReturn(true);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(true);
        ReflectionTestUtils.setField(service, "maxImageBytes", 25_000_000L);
        ReflectionTestUtils.setField(service, "maxVideoBytes", 1_000_000_000L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", "n/a", java.util.List.of()));
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        when(userResolver.getCurrentUser()).thenReturn(user);
        when(userResolver.getCurrentUserId()).thenReturn(user.getId());
        Mission mission = new Mission();
        mission.setId("mission-id");
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        when(missions.findById("mission-id")).thenReturn(Optional.of(mission));
        MissionStaffAssignment staff = new MissionStaffAssignment();
        staff.setStaff(user);
        when(staffAssignments.findByMissionIdAndStaffIdAndIsCurrentTrue("mission-id", user.getId()))
                .thenReturn(Optional.of(staff));
        Device device = new Device();
        device.setId("device-id");
        device.setDeviceCode("DEVICE-01");
        when(devices.getEntityById("device-id")).thenReturn(device);
        when(devices.getEntityById("DEVICE-01")).thenReturn(device);
        MissionDeviceAssignment assignment = new MissionDeviceAssignment();
        assignment.setMission(mission);
        assignment.setDevice(device);
        when(deviceAssignments.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("mission-id"))
                .thenReturn(java.util.List.of(assignment));
        when(deviceAssignments.findByMissionIdAndDeviceIdAndIsCurrentTrue("mission-id", "device-id"))
                .thenReturn(Optional.of(assignment));
        when(storage.bucket()).thenReturn("test-bucket");
        when(storage.prefix()).thenReturn("");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMismatchedMediaBeforeIssuingS3Url() {
        var request = request("IMAGE", "video/mp4");
        assertThatThrownBy(() -> service.prepare("mission-id", request))
                .isInstanceOf(ApiException.class).hasMessageContaining("disagree");
        verify(storage, never()).createPresignedPutUrl(any(), any(), anyLong(), any());
    }

    @Test
    void repeatPrepareUsesSameAttempt() {
        var request = request("IMAGE", "image/jpeg");
        MediaAsset existing = new MediaAsset();
        existing.setId("media-id");
        existing.setMissionId("mission-id");
        existing.setType("IMAGE");
        existing.setContentType("image/jpeg");
        existing.setFileSize(100L);
        existing.setChecksumSha256(request.getChecksumSha256());
        existing.setMediaStatus(MediaStatus.UPLOAD_PENDING);
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id", "device-id", "capture-1"))
                .thenReturn(Optional.of(existing));
        MediaUploadAttempt attempt = new MediaUploadAttempt();
        attempt.setId("attempt-id");
        attempt.setAttemptNumber(1);
        attempt.setStorageKey("staging/media-id/1.jpg");
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc("media-id"))
                .thenReturn(Optional.of(attempt));
        when(storage.createPresignedPutUrl(any(), any(), anyLong(), any()))
                .thenReturn(new IMediaObjectStorage.PresignedUpload("https://s3.example/upload", Map.of(), 900));

        var result = service.prepare("mission-id", request);

        assertThat(result.getMediaId()).isEqualTo("media-id");
        assertThat(result.getAttemptId()).isEqualTo("attempt-id");
        verify(media, never()).saveAndFlush(any());
    }

    @Test
    void completedMissionAllowsItsReleasedOperatorAndDrone() {
        Mission mission = missions.findById("mission-id").orElseThrow();
        mission.setStatus(MissionStatus.COMPLETED);
        when(staffAssignments.findByMissionIdAndStaffIdAndIsCurrentTrue(
                "mission-id", userResolver.getCurrentUserId()))
                .thenReturn(Optional.empty());
        MissionStaffAssignment staff = new MissionStaffAssignment();
        staff.setStaff(userResolver.getCurrentUser());
        staff.setReleaseReason("MISSION_COMPLETE");
        when(staffAssignments.findByMissionId("mission-id")).thenReturn(List.of(staff));
        MissionDeviceAssignment assignment = new MissionDeviceAssignment();
        assignment.setMission(mission);
        assignment.setDevice(devices.getEntityById("device-id"));
        assignment.setReleaseReason("MISSION_COMPLETE");
        when(deviceAssignments.findByMissionId("mission-id")).thenReturn(List.of(assignment));
        MediaAsset existing = new MediaAsset();
        existing.setId("media-id");
        existing.setMissionId("mission-id");
        existing.setType("IMAGE");
        existing.setContentType("image/jpeg");
        existing.setFileSize(100L);
        existing.setChecksumSha256("a".repeat(64));
        existing.setMediaStatus(MediaStatus.AVAILABLE);
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id", "device-id", "capture-1"))
                .thenReturn(Optional.of(existing));
        when(media.findById("media-id")).thenReturn(Optional.of(existing));

        assertThat(service.prepare("mission-id", request("IMAGE", "image/jpeg")).getStatus())
                .isEqualTo(MediaStatus.AVAILABLE);
    }

    @Test void deletedOrphanCaptureIsUploadedAgainUsingANewAttemptAndTheSameMediaIdentity() {
        var request=request("IMAGE","image/jpeg");
        var existing=new MediaAsset(); existing.setId("media-id");existing.setMissionId("mission-id");existing.setDeviceId("device-id");
        existing.setType("IMAGE");existing.setContentType("image/jpeg");existing.setFileSize(100L);existing.setChecksumSha256(request.getChecksumSha256());
        existing.setStorageStatus(com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETED);existing.setMediaStatus(MediaStatus.RETRY_REQUIRED);
        existing.setDeletedFromStorageAt(Instant.now());existing.setValidatedAt(Instant.now());
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id","device-id","capture-1")).thenReturn(Optional.of(existing));
        var old=new MediaUploadAttempt();old.setAttemptNumber(1);
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc("media-id")).thenReturn(Optional.of(old));
        when(storage.createPresignedPutUrl(any(),any(),anyLong(),any())).thenReturn(new IMediaObjectStorage.PresignedUpload("https://s3.example/new",Map.of(),900));
        var result=service.prepare("mission-id",request);
        assertThat(result.getMediaId()).isEqualTo("media-id");assertThat(result.getAttemptNumber()).isEqualTo(2);
        assertThat(existing.getStorageStatus()).isEqualTo(com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE);
        assertThat(existing.getDeletedFromStorageAt()).isNull();assertThat(existing.getValidatedAt()).isNull();
        assertThat(existing.getS3Key()).endsWith("/2.jpg");
    }

    @Test void aCaptureBeingDeletedCannotIssueAnotherSignedUploadUrl() {
        var request=request("IMAGE","image/jpeg");
        var existing=new MediaAsset();existing.setId("media-id");existing.setMissionId("mission-id");
        existing.setType("IMAGE");existing.setContentType("image/jpeg");existing.setFileSize(100L);existing.setChecksumSha256(request.getChecksumSha256());
        existing.setStorageStatus(com.ondemandmonitoring.media.enums.MediaStorageStatus.DELETE_PENDING);
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id","device-id","capture-1")).thenReturn(Optional.of(existing));
        assertThatThrownBy(()->service.prepare("mission-id",request)).isInstanceOf(ApiException.class);
        verify(storage,never()).createPresignedPutUrl(any(),any(),anyLong(),any());
    }

    private PrepareMediaUploadRequest request(String type, String contentType) {
        return new PrepareMediaUploadRequest("device-id", "capture-1", type, "capture.jpg",
                contentType, 100L, "a".repeat(64), Instant.now());
    }

    @Test
    void rejectsAFileThatIsAlreadyStoredForTheMission() {
        var request = request("IMAGE", "image/jpeg");
        when(media.existsActiveDuplicate("mission-id", request.getChecksumSha256())).thenReturn(true);
        assertThatThrownBy(() -> service.prepare("mission-id", request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.MEDIA_DUPLICATE_CONTENT));
        verify(media, never()).saveAndFlush(any());
    }

    @Test
    void rejectsACaptureOlderThanTheMissionsPlannedDuration() {
        Mission mission = missions.findById("mission-id").orElseThrow();
        Instant start = Instant.now().minusSeconds(3600);
        mission.setScheduledStartAt(start);
        mission.setScheduledEndAt(start.plusSeconds(1800)); // planned 30 minutes
        var old = request("IMAGE", "image/jpeg");
        old.setCapturedAt(Instant.now().minusSeconds(3 * 3600));
        assertThatThrownBy(() -> service.prepare("mission-id", old))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.MEDIA_CAPTURE_TOO_OLD));
    }

    @Test
    void acceptsACaptureInsideThePlannedDurationWindow() {
        Mission mission = missions.findById("mission-id").orElseThrow();
        Instant start = Instant.now().minusSeconds(3600);
        mission.setScheduledStartAt(start);
        mission.setScheduledEndAt(start.plusSeconds(1800));
        var recent = request("IMAGE", "image/jpeg");
        recent.setCapturedAt(Instant.now().minusSeconds(600));
        when(media.saveAndFlush(any())).thenAnswer(call -> {
            MediaAsset saved = call.getArgument(0); saved.setId("media-id"); return saved;
        });
        when(storage.createPresignedPutUrl(any(), any(), anyLong(), any()))
                .thenReturn(new IMediaObjectStorage.PresignedUpload("https://test/upload", Map.of(), 300));
        assertThat(service.prepare("mission-id", recent)).isNotNull();
    }

    @Test
    void preparePersistsDeclaredProvenanceWithoutInventingCaptureActor() {
        var request = request("IMAGE", "image/jpeg"); request.setSourceType("SATELLITE_SNAPSHOT");
        assertThatThrownBy(() -> service.prepare("mission-id", request))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unsupported media source type");
        verify(media, never()).saveAndFlush(any());
    }

    @Test
    void staffCanCreateDroneCaptureFromFileAfterFlightWithoutManualTaskAndRecordTransportChannel() {
        Mission mission = missions.findById("mission-id").orElseThrow();
        Instant now = Instant.now();
        mission.setStatus(MissionStatus.COMPLETED);
        mission.setActualStartAt(now.minusSeconds(900));
        mission.setActualEndAt(now.minusSeconds(300));
        var request = request("IMAGE", "image/jpeg");
        request.setCapturedAt(now.minusSeconds(600));
        request.setSourceType("DRONE_CAMERA");
        request.setCaptureTimeSource("METADATA");
        request.setCaptureTimeZone("+07:00");
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id", "device-id", "capture-1"))
                .thenReturn(Optional.empty());
        when(media.existsActiveDuplicate("mission-id", request.getChecksumSha256())).thenReturn(false);
        when(media.saveAndFlush(any())).thenAnswer(call -> {
            MediaAsset saved = call.getArgument(0); saved.setId("media-staff"); return saved;
        });
        when(storage.createPresignedPutUrl(any(), any(), anyLong(), any()))
                .thenReturn(new IMediaObjectStorage.PresignedUpload("https://test/staff-upload", Map.of(), 300));
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc("media-staff")).thenReturn(Optional.empty());

        var response = service.prepareStaffFile("mission-id", request);

        assertThat(response.getMediaId()).isEqualTo("media-staff");
        assertThat(response.getUploadChannel()).isEqualTo(MediaUploadChannel.STAFF_FILE);
        assertThat(response.getManualTaskId()).isNull();
        var asset = org.mockito.ArgumentCaptor.forClass(MediaAsset.class);
        verify(media).saveAndFlush(asset.capture());
        assertThat(asset.getValue().getSourceType()).isEqualTo("DRONE_CAMERA");
        assertThat(asset.getValue().getCaptureTimeSource()).isEqualTo("METADATA");
        assertThat(asset.getValue().getCaptureTimeZone()).isEqualTo("+07:00");
        assertThat(asset.getValue().getCaptureTimeConfirmedBy()).isEqualTo(userResolver.getCurrentUserId());
        assertThat(asset.getValue().getCaptureTimeConfirmedAt()).isNotNull();
        var attempt = org.mockito.ArgumentCaptor.forClass(MediaUploadAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().isManualAttempt()).isTrue();
        assertThat(attempt.getValue().getUploadChannel()).isEqualTo(MediaUploadChannel.STAFF_FILE);
        assertThat(attempt.getValue().getUploadedBy()).isEqualTo(userResolver.getCurrentUserId());
    }

    @Test
    void staffFileImportRejectsCaptureTimeOutsideTheFlightWindow() {
        Mission mission = missions.findById("mission-id").orElseThrow();
        Instant start = Instant.now().minusSeconds(3600);
        mission.setStatus(MissionStatus.COMPLETED);
        mission.setActualStartAt(start);
        mission.setActualEndAt(start.plusSeconds(300));
        var request = request("IMAGE", "image/jpeg");
        request.setCapturedAt(start.minusSeconds(3600));

        assertThatThrownBy(() -> service.prepareStaffFile("mission-id", request))
                .isInstanceOf(ApiException.class).hasMessageContaining("outside this Mission's flight window");
        verify(storage, never()).createPresignedPutUrl(any(), any(), anyLong(), any());
        verify(media, never()).saveAndFlush(any());
    }

    @Test
    void manualRecoveryCannotReplaceAvailableMediaOrReopenResolvedTask() {
        var asset = new MediaAsset(); asset.setId("existing"); asset.setMissionId("mission-id");
        asset.setFileSize(100L); asset.setContentType("image/jpeg"); asset.setChecksumSha256("a".repeat(64));
        when(media.findByIdForUpdate("existing")).thenReturn(Optional.of(asset));
        when(media.findById("existing")).thenReturn(Optional.of(asset));
        var request = new com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest();
        request.setFileSize(100L); request.setContentType("image/jpeg"); request.setChecksumSha256("a".repeat(64));
        asset.setMediaStatus(MediaStatus.AVAILABLE);
        assertThat(service.prepareManualFile("existing", request).getStatus()).isEqualTo(MediaStatus.AVAILABLE);
        var task = new ManualUploadTask(); task.setStatus(com.ondemandmonitoring.media.enums.ManualUploadTaskStatus.RESOLVED);
        when(manualTasks.findByMediaId("existing")).thenReturn(Optional.of(task));
        asset.setMediaStatus(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThatThrownBy(() -> service.prepareManualFile("existing", request)).isInstanceOf(ApiException.class);
        verify(attempts, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test
    void provenanceCannotBeChangedThroughIdempotentPrepare() {
        var request = request("IMAGE", "image/jpeg"); request.setSourceType("SATELLITE_SNAPSHOT");
        var saved = new MediaAsset(); saved.setSourceType("DRONE_CAMERA");
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id", "device-id", "capture-1"))
                .thenReturn(Optional.of(saved));
        assertThatThrownBy(() -> service.prepare("mission-id", request)).isInstanceOf(ApiException.class);
        verify(media, never()).saveAndFlush(any());
    }
    @Test void removedMediaCannotMintPartsRetryOrAcknowledgeUploadButStatusCanBeRead() {
        var removed=new MediaAsset();removed.setId("removed");removed.setMissionId("mission-id");removed.setMediaStatus(MediaStatus.REMOVED);
        when(media.findById("removed")).thenReturn(Optional.of(removed));when(media.findByIdForUpdate("removed")).thenReturn(Optional.of(removed));
        assertThatThrownBy(() -> service.presignPart("removed","attempt",1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.retry("removed",true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.markUploaded("removed","attempt")).isInstanceOf(ApiException.class);
        assertThat(service.status("removed").getStatus()).isEqualTo(MediaStatus.REMOVED);
        verifyNoInteractions(storage);
    }
    @Test void newMultipartPartUrlsExtendTheRecordedCleanupHorizon() {
        var asset=new MediaAsset();asset.setId("multipart");asset.setMissionId("mission-id");asset.setFileSize(50_000_000L);asset.setMediaStatus(MediaStatus.UPLOAD_PENDING);
        var attempt=new MediaUploadAttempt();attempt.setMedia(asset);attempt.setStatus(com.ondemandmonitoring.media.enums.UploadAttemptStatus.PENDING);
        attempt.setStorageKey("staging/multipart");attempt.setMultipartUploadId("upload");attempt.setExpiresAt(Instant.now().minusSeconds(3600));
        when(media.findByIdForUpdate("multipart")).thenReturn(Optional.of(asset));
        when(attempts.findByIdAndMediaId("attempt","multipart")).thenReturn(Optional.of(attempt));
        when(storage.createPresignedPartUrl("staging/multipart","upload",1)).thenReturn(new IMediaObjectStorage.PresignedUpload("https://example.test/part",Map.of(),600));
        var before=Instant.now(); service.presignPart("multipart","attempt",1);
        assertThat(attempt.getExpiresAt()).isAfter(before.plusSeconds(599));
    }

    @Test void selectingTheSameRemovedStaffFileReturnsAnExplicitConflictWithoutRecreatingIt() {
        Mission mission=missions.findById("mission-id").orElseThrow();Instant now=Instant.now();
        mission.setStatus(MissionStatus.COMPLETED);mission.setActualStartAt(now.minusSeconds(900));mission.setActualEndAt(now.minusSeconds(300));
        var request=request("IMAGE","image/jpeg");request.setCapturedAt(now.minusSeconds(600));request.setSourceType("DRONE_CAMERA");
        request.setCaptureTimeSource("METADATA");request.setCaptureTimeZone("+07:00");
        var removed=new MediaAsset();removed.setId("removed");removed.setMediaStatus(MediaStatus.REMOVED);removed.setSourceType("DRONE_CAMERA");
        removed.setType(request.getMediaType());removed.setContentType(request.getContentType());removed.setFileSize(request.getFileSize());removed.setChecksumSha256(request.getChecksumSha256());
        when(media.findByMissionIdAndDeviceIdAndLocalMediaId("mission-id","device-id","capture-1")).thenReturn(Optional.of(removed));
        assertThatThrownBy(() -> service.prepareStaffFile("mission-id",request)).isInstanceOfSatisfying(ApiException.class,e -> {
            assertThat(e.getErrorCode()).isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.MEDIA_REMOVED);
            assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        });
        assertThat(removed.getMediaStatus()).isEqualTo(MediaStatus.REMOVED);
        verify(media,never()).saveAndFlush(any());verify(attempts,never()).save(any());verifyNoInteractions(storage);
    }

    private PrepareMediaUploadRequest newStaffIntentRequest() {
        var mission=missions.findById("mission-id").orElseThrow();var now=Instant.now();
        mission.setStatus(MissionStatus.COMPLETED);mission.setActualStartAt(now.minusSeconds(900));mission.setActualEndAt(now.minusSeconds(300));
        var request=request("IMAGE","image/jpeg");request.setCapturedAt(now.minusSeconds(600));request.setSourceType("DRONE_CAMERA");
        request.setCaptureTimeSource("METADATA");request.setCaptureTimeZone("UTC");request.setLocalMediaId("staff-new-intent");
        return request;
    }
    @Test void aNewStaffIntentCreatesANewAssetWithoutRestoringTheRemovedIdentity() {
        var request=newStaffIntentRequest();
        when(media.existsActiveDuplicate("mission-id",request.getChecksumSha256())).thenReturn(false);
        when(media.saveAndFlush(any())).thenAnswer(call -> {MediaAsset created=call.getArgument(0);created.setId("new-media");return created;});
        when(storage.createPresignedPutUrl(any(),any(),anyLong(),any())).thenReturn(new IMediaObjectStorage.PresignedUpload("https://test/new-upload",Map.of(),300));
        var response=service.prepareStaffFile("mission-id",request);
        assertThat(response.getMediaId()).isEqualTo("new-media");assertThat(response.getStatus()).isEqualTo(MediaStatus.UPLOAD_PENDING);
        var saved=org.mockito.ArgumentCaptor.forClass(MediaAsset.class);verify(media).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLocalMediaId()).isEqualTo("staff-new-intent");
        assertThat(saved.getValue().getS3Key()).contains("/new-media/1.jpg");
        verify(media).findByMissionIdAndDeviceIdAndLocalMediaId("mission-id","device-id","staff-new-intent");
    }
    @Test void aNewIntentDoesNotBypassDeduplicationWhileTheContentIsStillActive() {
        var request=newStaffIntentRequest();when(media.existsActiveDuplicate("mission-id",request.getChecksumSha256())).thenReturn(true);
        assertThatThrownBy(() -> service.prepareStaffFile("mission-id",request)).isInstanceOfSatisfying(ApiException.class,e ->
            assertThat(e.getErrorCode()).isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.MEDIA_DUPLICATE_CONTENT));
        verify(media,never()).saveAndFlush(any());verify(storage,never()).createPresignedPutUrl(any(),any(),anyLong(),any());verify(storage,never()).createMultipartUpload(any(),any(),any());
    }

}
