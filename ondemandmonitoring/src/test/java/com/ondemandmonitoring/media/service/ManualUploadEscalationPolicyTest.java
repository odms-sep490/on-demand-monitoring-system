package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.service.IDeviceService;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.enums.ManualUploadTaskStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.ManualUploadTaskServiceImpl;
import com.ondemandmonitoring.media.service.impl.MediaUploadServiceImpl;
import com.ondemandmonitoring.media.service.impl.MediaValidationServiceImpl;
import com.ondemandmonitoring.media.service.impl.MediaObjectVerificationServiceImpl;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import com.ondemandmonitoring.mission.service.impl.MissionMediaAccessServiceImpl;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Escalation policy: a Staff Manual Upload ticket is raised only after STRICTLY MORE THAN
 * three automatic attempts actually FAILED (transfer failure or failed object validation).
 * Prepares, abandoned presigned URLs and never-transferred retries must never count.
 */
class ManualUploadEscalationPolicyTest {

    private static final String MEDIA_ID = "media-id";

    private final MissionRepository missions = mock(MissionRepository.class);
    private final MissionDeviceAssignmentRepository deviceAssignments =
            mock(MissionDeviceAssignmentRepository.class);
    private final MissionStaffAssignmentRepository staffAssignments =
            mock(MissionStaffAssignmentRepository.class);
    private final IDeviceService devices = mock(IDeviceService.class);
    private final MediaAssetRepository media = mock(MediaAssetRepository.class);
    private final MediaUploadAttemptRepository attempts = mock(MediaUploadAttemptRepository.class);
    private final ManualUploadTaskRepository manualTasks = mock(ManualUploadTaskRepository.class);
    private final MediaAuditLogRepository audit = mock(MediaAuditLogRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final AuthenticatedUserResolver userResolver = mock(AuthenticatedUserResolver.class);
    private final IMissionAuthorizationService authorization = mock(IMissionAuthorizationService.class);
    private final IMissionMediaEvidenceGuard missionLock = mock(IMissionMediaEvidenceGuard.class);
    private final StorageEventInboxRepository inbox = mock(StorageEventInboxRepository.class);

    private final IMediaAuditService auditService = mock(IMediaAuditService.class);

    private ManualUploadTaskServiceImpl manualTaskService;
    private MediaUploadServiceImpl service;
    private MediaAsset captured;
    private User operator;

    /**
     * The attempt rows for this media asset. Statuses are read straight off the entities,
     * so the tally always reflects what the service actually persisted.
     */
    private final List<MediaUploadAttempt> attemptRows = new ArrayList<>();
    private final AtomicInteger taskInserts = new AtomicInteger(0);
    private final AtomicInteger existingTaskSaves = new AtomicInteger(0);
    private final AtomicInteger auditLogWrites = new AtomicInteger(0);
    private ManualUploadTask storedTask;

    @BeforeEach
    void setUp() {
        attemptRows.clear();
        taskInserts.set(0);
        existingTaskSaves.set(0);
        auditLogWrites.set(0);
        storedTask = null;

        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        when(authorization.canOperatePayload("mission-id")).thenReturn(true);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(true);

        MissionMediaAccessServiceImpl missionAccess = new MissionMediaAccessServiceImpl(
                missions, deviceAssignments, userResolver, authorization);
        manualTaskService = new ManualUploadTaskServiceImpl(manualTasks);
        service = new MediaUploadServiceImpl(missionAccess, devices, media, attempts, manualTasks,
                manualTaskService, audit, storage, userResolver, missionLock, new MediaUploadPolicy());
        ReflectionTestUtils.setField(service, "maxImageBytes", 25_000_000L);
        ReflectionTestUtils.setField(service, "maxVideoBytes", 1_000_000_000L);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", "n/a", List.of()));
        operator = new User();
        operator.setId(UUID.randomUUID().toString());
        when(userResolver.getCurrentUser()).thenReturn(operator);
        when(userResolver.getCurrentUserId()).thenReturn(operator.getId());

        Mission mission = new Mission();
        mission.setId("mission-id");
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        when(missions.findById("mission-id")).thenReturn(Optional.of(mission));
        MissionStaffAssignment staff = new MissionStaffAssignment();
        staff.setStaff(operator);
        when(staffAssignments.findByMissionIdAndStaffIdAndIsCurrentTrue("mission-id", operator.getId()))
                .thenReturn(Optional.of(staff));
        Device device = new Device();
        device.setId("device-id");
        when(devices.getEntityById("device-id")).thenReturn(device);

        captured = new MediaAsset();
        captured.setId(MEDIA_ID);
        captured.setMissionId("mission-id");
        captured.setDeviceId("device-id");
        captured.setLocalMediaId("capture-1");
        captured.setOperatorId(operator.getId());
        captured.setType("IMAGE");
        captured.setSourceType("DRONE_CAMERA");
        captured.setContentType("image/jpeg");
        captured.setFileSize(100L);
        captured.setChecksumSha256("a".repeat(64));
        captured.setOriginalFileName("capture.jpg");
        captured.setMediaStatus(MediaStatus.UPLOAD_PENDING);
        when(media.findById(MEDIA_ID)).thenReturn(Optional.of(captured));
        when(media.findByIdForUpdate(MEDIA_ID)).thenReturn(Optional.of(captured));

        when(storage.bucket()).thenReturn("test-bucket");
        when(storage.prefix()).thenReturn("");
        when(storage.createPresignedPutUrl(any(), any(), anyLong(), any()))
                .thenReturn(new IMediaObjectStorage.PresignedUpload("https://s3.example/upload", Map.of(), 900));

        // Production query: FAILED automatic attempts only.
        when(attempts.countByMediaIdAndManualAttemptFalseAndStatus(eq(MEDIA_ID), eq(UploadAttemptStatus.FAILED)))
                .thenAnswer(call -> attemptRows.stream()
                        .filter(a -> !a.isManualAttempt() && a.getStatus() == UploadAttemptStatus.FAILED)
                        .count());
        when(attempts.save(any(MediaUploadAttempt.class))).thenAnswer(call -> call.getArgument(0));

        when(manualTasks.findByMediaId(MEDIA_ID)).thenAnswer(call -> Optional.ofNullable(storedTask));
        when(manualTasks.save(any(ManualUploadTask.class))).thenAnswer(call -> {
            ManualUploadTask task = call.getArgument(0);
            if (task.getId() == null) {
                task.setId("task-" + taskInserts.incrementAndGet());
            } else {
                existingTaskSaves.incrementAndGet();
            }
            storedTask = task;
            return task;
        });
        // Emulates the JPQL query filtering status = OPEN, which is what Staff actually reads.
        when(manualTasks.findOpenByMissionId("mission-id")).thenAnswer(call ->
                storedTask != null && storedTask.getStatus() == ManualUploadTaskStatus.OPEN
                        ? List.of(storedTask) : List.of());
        when(media.save(any(MediaAsset.class))).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> { auditLogWrites.incrementAndGet(); return null; })
                .when(auditService).record(any(), any(), any(),
                        any(com.ondemandmonitoring.media.enums.MediaAuditAction.class), any());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private MediaUploadAttempt pendingAttempt(int number) {
        MediaUploadAttempt attempt = new MediaUploadAttempt();
        attempt.setId("attempt-" + number);
        attempt.setMedia(captured);
        attempt.setAttemptNumber(number);
        attempt.setStatus(UploadAttemptStatus.PENDING);
        attempt.setStorageKey("staging/mission/device/" + MEDIA_ID + "/" + number + ".jpg");
        attempt.setManualAttempt(false);
        attempt.setExpiresAt(Instant.now().plusSeconds(900));
        return attempt;
    }

    /** Prepares an attempt row without transferring any bytes. */
    private MediaUploadAttempt prepareAttemptWithoutTransfer() {
        int number = attemptRows.size() + 1;
        MediaUploadAttempt attempt = pendingAttempt(number);
        attemptRows.add(attempt);
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc(MEDIA_ID)).thenReturn(Optional.of(attempt));
        when(attempts.findByIdAndMediaId("attempt-" + number, MEDIA_ID)).thenReturn(Optional.of(attempt));
        return attempt;
    }

    /** Reports one genuine failed transfer; the row flips to FAILED inside the service. */
    private MediaStatus reportOneFailedTransfer(int number) {
        MediaUploadAttempt attempt = pendingAttempt(number);
        attemptRows.add(attempt);
        when(attempts.findByIdAndMediaId("attempt-" + number, MEDIA_ID)).thenReturn(Optional.of(attempt));
        return service.reportFailure(MEDIA_ID, "attempt-" + number,
                ReportUploadFailureRequest.builder().code("TRANSFER_FAILED").message("transfer failed #" + number).build()).getStatus();
    }

    private List<MediaStatus> failTransfers(int failures) {
        var observed = new ArrayList<MediaStatus>();
        for (int i = 1; i <= failures; i++) observed.add(reportOneFailedTransfer(i));
        return observed;
    }

    // ---------- 1. Failures 1-3 must not create a ticket ----------

    @Test
    void firstThreeFailedAutomaticTransfersNeverCreateATicket() {
        var statuses = failTransfers(3);

        assertThat(statuses).containsExactly(
                MediaStatus.RETRY_REQUIRED, MediaStatus.RETRY_REQUIRED, MediaStatus.RETRY_REQUIRED);
        assertThat(taskInserts.get()).isZero();
        verify(manualTasks, never()).save(any(ManualUploadTask.class));
        assertThat(manualTasks.findOpenByMissionId("mission-id")).isEmpty();
    }

    // ---------- 2. The fourth failure creates exactly one OPEN ticket ----------

    @Test
    void fourthFailedAutomaticTransferCreatesExactlyOneOpenTicket() {
        var statuses = failTransfers(4);

        assertThat(statuses).containsExactly(
                MediaStatus.RETRY_REQUIRED, MediaStatus.RETRY_REQUIRED, MediaStatus.RETRY_REQUIRED,
                MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(storedTask.getMedia().getId()).isEqualTo(MEDIA_ID);
        assertThat(storedTask.getAssignedOperatorId()).isEqualTo(operator.getId());
        assertThat(storedTask.getReason()).isEqualTo("transfer failed #4");
        // Visible through the real Staff list query, not just a direct lookup.
        assertThat(manualTasks.findOpenByMissionId("mission-id")).hasSize(1);
    }

    // ---------- 3 & 4. Prepares and cancellations are not failures ----------

    @Test
    void preparingOrCancellingBeforeTransferNeverCountsAsAFailedTransfer() {
        prepareAttemptWithoutTransfer();
        prepareAttemptWithoutTransfer();
        prepareAttemptWithoutTransfer();
        prepareAttemptWithoutTransfer();
        // The presigned attempt is simply abandoned; nothing is reported as failed.
        assertThat(attemptRows).allMatch(a -> a.getStatus() == UploadAttemptStatus.PENDING);

        // An automatic retry must still be allowed because the failure tally is empty.
        captured.setMediaStatus(MediaStatus.RETRY_REQUIRED);
        var plan = service.retry(MEDIA_ID, false);

        assertThat(plan.getUploadUrl()).isNotNull();
        assertThat(taskInserts.get()).isZero();
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.UPLOAD_PENDING);
    }

    // ---------- 5. A successful transfer does not increment the failure count ----------

    @Test
    void successfulTransferAcknowledgementDoesNotCountAsAFailedTransfer() {
        var attempt = prepareAttemptWithoutTransfer();

        service.markUploaded(MEDIA_ID, attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo(UploadAttemptStatus.UPLOADED);
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.VALIDATING);
        assertThat(taskInserts.get()).isZero();
        verify(manualTasks, never()).save(any(ManualUploadTask.class));
    }

    // ---------- 7. Duplicate / repeated failures reuse the single ticket ----------

    @Test
    void repeatedAndDuplicateFailureCallbacksCreateOnlyOneTicket() {
        failTransfers(4);
        failTransfers(2);            // further genuine failures after escalation

        // Replayed callback for an attempt already marked FAILED.
        var replay = service.reportFailure(MEDIA_ID, "attempt-1",
                ReportUploadFailureRequest.builder().code("TRANSFER_FAILED").message("replay").build());

        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(existingTaskSaves.get()).isPositive();
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(manualTasks.findOpenByMissionId("mission-id")).hasSize(1);
        assertThat(replay.getMediaId()).isEqualTo(MEDIA_ID);
    }

    @Test
    void aResolvedTicketIsNotReopenedByALateDuplicateCallback() {
        failTransfers(4);
        // Simulate the successful validation pipeline having resolved the ticket.
        storedTask.setStatus(ManualUploadTaskStatus.RESOLVED);
        storedTask.setResolvedAt(Instant.now());

        failTransfers(2);

        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.RESOLVED);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(manualTasks.findOpenByMissionId("mission-id")).isEmpty();
    }

    // ---------- 6. Validation failure classification ----------

    private MediaValidationServiceImpl validationService() {
        return new MediaValidationServiceImpl(attempts, media, inbox, manualTasks, manualTaskService,
                auditService, new MediaUploadPolicy(), storage,
                new MediaObjectVerificationServiceImpl(storage), missionLock);
    }

    /** An already-persisted FAILED automatic attempt row. */
    private MediaUploadAttempt failedAttempt(int number) {
        MediaUploadAttempt attempt = pendingAttempt(number);
        attempt.setStatus(UploadAttemptStatus.FAILED);
        return attempt;
    }

    private MediaUploadAttempt validationAttempt(boolean manual) {
        MediaUploadAttempt attempt = new MediaUploadAttempt();
        attempt.setId("validation-attempt");
        attempt.setMedia(captured);
        attempt.setManualAttempt(manual);
        attempt.setStatus(UploadAttemptStatus.UPLOADED);
        return attempt;
    }

    private void arrangeValidationFailure(MediaUploadAttempt attempt) {
        captured.setMediaStatus(MediaStatus.VALIDATING);
        String key = "drone-media/staging/" + MEDIA_ID + ".jpg";
        when(storage.bucket()).thenReturn("test-bucket");
        captured.setS3Bucket("test-bucket");
        when(attempts.findMediaIdByStorageKey(key)).thenReturn(Optional.of(MEDIA_ID));
        when(media.findByIdForUpdate(MEDIA_ID)).thenReturn(Optional.of(captured));
        when(attempts.findByStorageKeyForUpdate(key)).thenReturn(Optional.of(attempt));
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc(MEDIA_ID)).thenReturn(Optional.of(attempt));
        // The attempt's own row lives in the tally and flips to FAILED when it is saved.
        attemptRows.add(attempt);
        // Size mismatch => checksum/signature validation failure.
        when(storage.inspect("test-bucket", key))
                .thenReturn(new IMediaObjectStorage.StoredObjectInfo(7L, "image/jpeg", Map.of()));
    }

    @Test
    void checksumValidationFailureEscalatesOnceTheFailureTallyReachesFour() {
        var attempt = validationAttempt(false);
        arrangeValidationFailure(attempt);
        // Three prior genuine failures; this attempt's row is added by the arrangement helper.
        attemptRows.add(failedAttempt(1));
        attemptRows.add(failedAttempt(2));
        attemptRows.add(failedAttempt(3));

        validationService().processObjectCreated("test-bucket", "drone-media/staging/" + MEDIA_ID + ".jpg", "evt-1");

        // This attempt was persisted FAILED, so the tally is 4 -> escalate.
        assertThat(attempt.getStatus()).isEqualTo(UploadAttemptStatus.FAILED);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
    }

    @Test
    void validationFailureCannotCreateATicketFromUnattemptedRetries() {
        var attempt = validationAttempt(false);
        // Six prepared-but-never-transferred attempts: not one genuine failure.
        for (int i = 0; i < 6; i++) attemptRows.add(pendingAttempt(100 + i));
        arrangeValidationFailure(attempt);

        validationService().processObjectCreated("test-bucket", "drone-media/staging/" + MEDIA_ID + ".jpg", "evt-1");

        assertThat(attempt.getStatus()).isEqualTo(UploadAttemptStatus.FAILED);
        assertThat(taskInserts.get()).isZero();
        verify(manualTasks, never()).save(any(ManualUploadTask.class));
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
    }

    // ---------- 8. Validation-created ticket is visible to Staff ----------

    @Test
    void validationTriggeredTicketIsOpenAndListedForStaff() {
        var attempt = validationAttempt(true);   // manual attempt always escalates
        arrangeValidationFailure(attempt);

        validationService().processObjectCreated("test-bucket", "drone-media/staging/" + MEDIA_ID + ".jpg", "evt-2");

        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(storedTask.getStatus())
                .as("a null status would hide the ticket from the status = OPEN Staff query")
                .isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(storedTask.getReason()).contains("differs");
        assertThat(manualTasks.findOpenByMissionId("mission-id")).hasSize(1);
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        // Never AVAILABLE: validation failure must not promote media.
        assertThat(captured.getValidatedAt()).isNull();
        assertThat(captured.getAvailableAt()).isNull();
    }

    @Test
    void helperAlwaysAssignsOpenRegardlessOfConstructionPath() {
        // Verified: Lombok's @NoArgsConstructor DOES apply @Builder.Default, so a raw
        // `new ManualUploadTask()` is already OPEN. The helper sets it explicitly anyway
        // so the invariant cannot silently regress if the entity defaults ever change.
        assertThat(new ManualUploadTask().getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);

        manualTaskService.requireOpen(captured, "reason");
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(storedTask.getMedia()).isSameAs(captured);
        assertThat(storedTask.getAssignedOperatorId()).isEqualTo(operator.getId());
    }

    // ---------- 9. Manual file upload still uses the verification pipeline ----------

    @Test
    void manualUploadRequiresAnExactBackupCopy() {
        failTransfers(4);

        assertThatThrownBy(() -> service.prepareManualFile(MEDIA_ID,
                new ManualMediaFileRequest(100L, "image/jpeg", "b".repeat(64))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not an exact copy");

        when(manualTasks.findByMediaId(MEDIA_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepareManualFile(MEDIA_ID,
                new ManualMediaFileRequest(100L, "image/jpeg", "a".repeat(64))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No manual task exists");
    }

    @Test
    void manualUploadOfExactBackupReusesMediaAndEntersTheSameValidationPipeline() {
        failTransfers(4);
        MediaUploadAttempt latest = pendingAttempt(4);
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc(MEDIA_ID)).thenReturn(Optional.of(latest));
        List<MediaUploadAttempt> savedAttempts = new ArrayList<>();
        when(attempts.save(any(MediaUploadAttempt.class))).thenAnswer(call -> {
            MediaUploadAttempt saved = call.getArgument(0);
            if (saved.getId() == null) saved.setId("manual-attempt-4");
            savedAttempts.add(saved);
            return saved;
        });

        var plan = service.prepareManualFile(MEDIA_ID,
                new ManualMediaFileRequest(100L, "image/jpeg", "a".repeat(64)));

        assertThat(plan.getMediaId()).isEqualTo(MEDIA_ID);
        assertThat(plan.getUploadUrl()).isNotNull();
        // Same MediaAsset => mission/device/local-id/sourceType identity is preserved.
        assertThat(captured.getSourceType()).isEqualTo("DRONE_CAMERA");
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.UPLOAD_PENDING);
        // A manual attempt is never itself a qualifying automatic failure.
        assertThat(savedAttempts).allMatch(a -> a.isManualAttempt());
        // The ticket survives and stays visible while the manual upload is pending.
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(manualTasks.findOpenByMissionId("mission-id")).hasSize(1);
    }

    @Test
    void manualRetryIsRefusedWhenManualUploadIsNotRequired() {
        captured.setMediaStatus(MediaStatus.RETRY_REQUIRED);
        assertThatThrownBy(() -> service.retry(MEDIA_ID, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Manual upload is not required");
    }

    @Test
    void exhaustedAutomaticRetryEscalatesInsteadOfPreparingAnotherAttempt() {
        failTransfers(4);
        // Still RETRY_REQUIRED so the automatic-retry precondition is met and the
        // failure tally (not the status) is what forces the escalation.
        captured.setMediaStatus(MediaStatus.RETRY_REQUIRED);

        var plan = service.retry(MEDIA_ID, false);

        assertThat(plan.getStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(manualTasks.findOpenByMissionId("mission-id")).hasSize(1);
    }

    @Test
    void failedUploadNeverMarksMediaAvailableOrValidated() {
        failTransfers(4);

        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(captured.getValidatedAt()).isNull();
        assertThat(captured.getAvailableAt()).isNull();
        verify(media, never()).saveAndFlush(any());
    }

    // ---------- authorization ----------

    @Test
    void unauthorizedOperatorCannotListOrPrepareManualUploads() {
        failTransfers(4);
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(false);
        when(authorization.canOperatePayload("mission-id")).thenReturn(false);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(false);
        when(staffAssignments.findByMissionIdAndStaffIdAndIsCurrentTrue("mission-id", operator.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.manualTasks("mission-id")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.prepareManualFile(MEDIA_ID,
                new ManualMediaFileRequest(100L, "image/jpeg", "a".repeat(64)))).isInstanceOf(ApiException.class);
    }

    @Test
    void staffListReturnsMissionScopedOpenTicketsWithReasonAndIdentity() {
        failTransfers(4);
        var listed = service.manualTasks("mission-id");

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getManualTaskId()).isEqualTo("task-1");
        assertThat(listed.get(0).getMissionId()).isEqualTo("mission-id");
        assertThat(listed.get(0).getBackendMediaId()).isEqualTo(MEDIA_ID);
        assertThat(listed.get(0).getReason()).isEqualTo("transfer failed #4");
        assertThat(listed.get(0).getChecksumSha256()).isEqualTo("a".repeat(64));
    }
}
