package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest;
import com.ondemandmonitoring.media.enums.ManualUploadTaskStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.*;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * End-to-end Manual Upload RECOVERY workflow (business definition steps 3-9):
 * failed automatic transfer -> one OPEN ticket -> Staff uploads the ORIGINAL file from the
 * drone memory card -> backend verifies it against the recorded capture manifest -> the
 * recovered asset keeps its capture-origin sourceType and stays eligible as checklist evidence.
 *
 * Uses the real {@link ChecklistEvidencePolicy} so Policy V2 stays authoritative.
 */
class ManualUploadRecoveryWorkflowTest {

    private static final String MEDIA_ID = "recovered-media";
    private static final String STAGING_KEY = "staging/mission-1/device-1/recovered-media/5.jpg";
    private static final byte[] ORIGINAL_BYTES = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x10, 0x20, 0x30};

    private final MediaAssetRepository media = mock(MediaAssetRepository.class);
    private final MediaUploadAttemptRepository attempts = mock(MediaUploadAttemptRepository.class);
    private final ManualUploadTaskRepository manualTasks = mock(ManualUploadTaskRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final StorageEventInboxRepository inbox = mock(StorageEventInboxRepository.class);

    private final IMediaAuditService auditService = mock(IMediaAuditService.class);

    private final ManualUploadTaskServiceImpl manualTaskService = new ManualUploadTaskServiceImpl(manualTasks);
    private final ChecklistEvidencePolicy evidencePolicy = new ChecklistEvidencePolicy();
    private MediaValidationServiceImpl validationService;

    private MediaAsset captured;
    private final List<MediaUploadAttempt> attemptRows = new ArrayList<>();
    private ManualUploadTask storedTask;

    @BeforeEach
    void setUp() throws Exception {
        attemptRows.clear();
        storedTask = null;

        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ORIGINAL_BYTES));

        captured = new MediaAsset();
        captured.setId(MEDIA_ID);
        captured.setMissionId("mission-1");
        captured.setDeviceId("device-1");
        captured.setLocalMediaId("capture-77");
        captured.setOperatorId("operator-1");
        captured.setType("IMAGE");
        captured.setSourceType("DRONE_CAMERA");     // capture origin, not transport
        captured.setStorageProvider("S3");
        captured.setContentType("image/jpeg");
        captured.setFileSize((long) ORIGINAL_BYTES.length);
        captured.setChecksumSha256(checksum);
        captured.setOriginalFileName("DJI_0077.jpg");
        captured.setS3Bucket("test-bucket");
        captured.setMediaStatus(MediaStatus.UPLOAD_PENDING);

        when(media.findById(MEDIA_ID)).thenReturn(Optional.of(captured));
        when(media.findByIdForUpdate(MEDIA_ID)).thenReturn(Optional.of(captured));
        when(media.save(any(MediaAsset.class))).thenAnswer(c -> c.getArgument(0));
        when(storage.bucket()).thenReturn("test-bucket");
        when(storage.prefix()).thenReturn("");
        when(attempts.countByMediaIdAndManualAttemptFalseAndStatus(eq(MEDIA_ID), eq(UploadAttemptStatus.FAILED)))
                .thenAnswer(c -> attemptRows.stream()
                        .filter(a -> !a.isManualAttempt() && a.getStatus() == UploadAttemptStatus.FAILED)
                        .count());
        when(attempts.save(any(MediaUploadAttempt.class))).thenAnswer(c -> c.getArgument(0));
        when(manualTasks.findByMediaId(MEDIA_ID)).thenAnswer(c -> Optional.ofNullable(storedTask));
        when(manualTasks.save(any(ManualUploadTask.class))).thenAnswer(c -> {
            ManualUploadTask t = c.getArgument(0);
            if (t.getId() == null) t.setId("task-1");
            storedTask = t;
            return t;
        });

        // Recovery is asserted through the ticket lifecycle and the real validation pipeline.
        validationService = new MediaValidationServiceImpl(attempts, media, inbox, manualTasks,
                manualTaskService, auditService, new MediaUploadPolicy(), storage,
                new MediaObjectVerificationServiceImpl(storage),
                mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class));
    }

    private MediaUploadAttempt automaticAttempt(int n) {
        MediaUploadAttempt a = new MediaUploadAttempt();
        a.setId("attempt-" + n);
        a.setMedia(captured);
        a.setAttemptNumber(n);
        a.setStatus(UploadAttemptStatus.PENDING);
        a.setStorageKey("staging/mission-1/device-1/" + MEDIA_ID + "/" + n + ".jpg");
        a.setManualAttempt(false);
        a.setExpiresAt(Instant.now().plusSeconds(900));
        attemptRows.add(a);
        return a;
    }

    /** Creates one OPEN ticket as the upload service would, using the shared helper. */
    private ManualUploadTask escalate(String reason) {
        captured.setMediaStatus(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        captured.setValidationError(reason);
        media.save(captured);
        return manualTaskService.requireOpen(captured, reason);
    }

    // ---------- Steps 3 & 4: fourth failure -> one idempotent OPEN ticket with exact identity ----------

    @Test
    void ticketKeepsExactMissionMediaAndOriginIdentity() {
        ManualUploadTask task = escalate("Automatic attempts exhausted");

        assertThat(task.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(task.getMedia().getId()).isEqualTo(MEDIA_ID);
        assertThat(task.getMedia().getMissionId()).isEqualTo("mission-1");
        assertThat(task.getMedia().getLocalMediaId()).isEqualTo("capture-77");
        assertThat(task.getAssignedOperatorId()).isEqualTo("operator-1");
        assertThat(task.getReason()).isEqualTo("Automatic attempts exhausted");

        // Repeated escalation reuses the same row rather than creating a second ticket.
        ManualUploadTask again = escalate("Automatic attempts exhausted");
        assertThat(again.getId()).isEqualTo(task.getId());
        assertThat(storedTask.getId()).isEqualTo("task-1");
    }

    @Test
    void staffTicketListingExposesTheManifestStaffNeedsToMatchTheMemoryCard() {
        escalate("Automatic attempts exhausted");

        var response = Mappers.getMapper(MediaWorkflowMapper.class).toManualResponse(storedTask);

        assertThat(response.getMissionId()).isEqualTo("mission-1");
        assertThat(response.getBackendMediaId()).isEqualTo(MEDIA_ID);
        assertThat(response.getLocalMediaId()).isEqualTo("capture-77");
        assertThat(response.getDeviceId()).isEqualTo("device-1");
        assertThat(response.getFileName()).isEqualTo("DJI_0077.jpg");
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getFileSize()).isEqualTo((long) ORIGINAL_BYTES.length);
        assertThat(response.getChecksumSha256()).isEqualTo(captured.getChecksumSha256());
        // Staff must see the capture origin so they know which card file to pull.
        assertThat(response.getSourceType()).isEqualTo("DRONE_CAMERA");
        assertThat(response.getReason()).isEqualTo("Automatic attempts exhausted");
    }

    // ---------- Steps 5-7: manual upload of the ORIGINAL file, verified against the manifest ----------

    private MediaUploadAttempt manualAttemptInFlight() {
        MediaUploadAttempt a = new MediaUploadAttempt();
        a.setId("manual-attempt");
        a.setMedia(captured);
        a.setAttemptNumber(attemptRows.size() + 1);
        a.setStatus(UploadAttemptStatus.UPLOADED);
        a.setStorageKey("staging/mission-1/device-1/" + MEDIA_ID + "/5.jpg");
        a.setManualAttempt(true);
        a.setExpiresAt(Instant.now().plusSeconds(900));
        attemptRows.add(a);
        return a;
    }

    private void arrangeObjectPresent(byte[] content) {
        captured.setS3Key(STAGING_KEY);
        manualAttemptInFlight();
        String key = captured.getS3Key();
        when(attempts.findMediaIdByStorageKey(key)).thenReturn(Optional.of(MEDIA_ID));
        when(attempts.findByStorageKeyForUpdate(key)).thenAnswer(c -> attemptRows.stream()
                .filter(a -> key.equals(a.getStorageKey())).findFirst());
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc(MEDIA_ID))
                .thenAnswer(c -> attemptRows.stream().reduce((a, b) -> b));
        when(storage.inspect("test-bucket", key)).thenReturn(new IMediaObjectStorage.StoredObjectInfo(
                (long) content.length, "image/jpeg",
                Map.of("media-id", MEDIA_ID, "sha256", captured.getChecksumSha256())));
        when(storage.open("test-bucket", key)).thenReturn(new IMediaObjectStorage.StoredObjectStream(
                new ByteArrayInputStream(content), (long) content.length, "image/jpeg"));
    }

    @Test
    void recoveredOriginalPassesVerificationAndKeepsDroneCameraSourceType() {
        escalate("Automatic attempts exhausted");
        arrangeObjectPresent(ORIGINAL_BYTES);

        validationService.processObjectCreated("test-bucket", STAGING_KEY, "recovery-event");

        // Technical validation makes the media available; the Manager approves the whole result once, not each file.
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.AVAILABLE);
        assertThat(captured.getValidatedAt()).isNotNull();
        assertThat(captured.getValidationError()).isNull();
        // Step 7: capture origin survives recovery; it is NOT rewritten to MANUAL_UPLOAD.
        assertThat(captured.getSourceType()).isEqualTo("DRONE_CAMERA");
        assertThat(captured.getAvailableAt()).isNotNull();
        // Step 9: resolved only after successful validation.
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.RESOLVED);
        assertThat(storedTask.getResolvedAt()).isNotNull();
    }

    @Test
    void manifestMismatchIsRejectedAndLeavesTicketOpen() {
        // A different file from the memory card: same length, different bytes.
        byte[] tampered = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0x99, 0x20, 0x30};
        escalate("Automatic attempts exhausted");
        arrangeObjectPresent(tampered);

        validationService.processObjectCreated("test-bucket", STAGING_KEY, "mismatch-event");

        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(captured.getValidationError()).contains("checksum");
        assertThat(captured.getValidatedAt()).isNull();
        // Ticket stays OPEN so Staff can pull the correct file.
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(storedTask.getResolvedAt()).isNull();
    }

    @Test
    void wrongContentTypeIsRejectedBeforeTheAssetIsPromoted() {
        escalate("Automatic attempts exhausted");
        manualAttemptInFlight();
        String key = STAGING_KEY;
        when(attempts.findMediaIdByStorageKey(key)).thenReturn(Optional.of(MEDIA_ID));
        when(attempts.findByStorageKeyForUpdate(key)).thenAnswer(c -> attemptRows.stream()
                .filter(a -> key.equals(a.getStorageKey())).findFirst());
        when(attempts.findFirstByMediaIdOrderByAttemptNumberDesc(MEDIA_ID))
                .thenAnswer(c -> attemptRows.stream().reduce((a, b) -> b));
        // Declared as video/mp4 although the capture manifest says image/jpeg.
        when(storage.inspect("test-bucket", key)).thenReturn(new IMediaObjectStorage.StoredObjectInfo(
                (long) ORIGINAL_BYTES.length, "video/mp4",
                Map.of("media-id", MEDIA_ID, "sha256", captured.getChecksumSha256())));
        when(storage.open("test-bucket", key)).thenReturn(new IMediaObjectStorage.StoredObjectStream(
                new ByteArrayInputStream(ORIGINAL_BYTES), (long) ORIGINAL_BYTES.length, "video/mp4"));

        validationService.processObjectCreated("test-bucket", key, "ct-event");

        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(captured.getValidationError()).contains("content type");
        assertThat(captured.getValidatedAt()).isNull();
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
    }

    @Test
    void manualFileUploadRejectsAFileThatDoesNotMatchTheRecordedManifest() {
        escalate("Automatic attempts exhausted");

        // Wrong size.
        assertThatThrownBy(() -> verifyAgainstManifest(new ManualMediaFileRequest(
                99L, "image/jpeg", captured.getChecksumSha256())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not an exact copy");

        // Wrong checksum even though size and content type match.
        assertThatThrownBy(() -> verifyAgainstManifest(new ManualMediaFileRequest(
                (long) ORIGINAL_BYTES.length, "image/jpeg", "b".repeat(64))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not an exact copy");
    }

    /** Mirrors MediaUploadServiceImpl.prepareManualFile's manifest guard. */
    private void verifyAgainstManifest(ManualMediaFileRequest request) {
        if (!captured.getFileSize().equals(request.getFileSize())
                || !captured.getContentType().equalsIgnoreCase(request.getContentType())
                || !captured.getChecksumSha256().equalsIgnoreCase(request.getChecksumSha256())) {
            throw new ApiException(com.ondemandmonitoring.common.exception.ErrorCode.MEDIA_IDEMPOTENCY_CONFLICT,
                    "Selected file is not an exact copy of the original capture");
        }
    }

    // ---------- Step 9: late callbacks must not reopen a resolved ticket ----------

    @Test
    void lateDuplicateCallbackCannotReopenAResolvedTicket() {
        escalate("Automatic attempts exhausted");
        arrangeObjectPresent(ORIGINAL_BYTES);
        validationService.processObjectCreated("test-bucket", STAGING_KEY, "first-event");
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.RESOLVED);

        // A late duplicate escalation from another path must not undo the resolution.
        manualTaskService.requireOpen(captured, "Automatic attempts exhausted");

        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.RESOLVED);
    }

    // ---------- Steps 7-8: recovered media remains eligible checklist evidence ----------

    @Test
    void recoveredMediaIsEligibleChecklistEvidenceAndCountsTowardsPolicyV2() {
        escalate("Automatic attempts exhausted");
        arrangeObjectPresent(ORIGINAL_BYTES);
        validationService.processObjectCreated("test-bucket", STAGING_KEY, "eligibility-event");

        var execution = new com.ondemandmonitoring.mission.domain.MissionChecklistExecution();
        execution.setId("exec-1");
        var item = new com.ondemandmonitoring.order.domain.OrderChecklistItem();
        item.setId("item-1");
        item.setEvidencePolicyVersion(2);
        item.setMinimumEvidenceCount(2);
        item.setRequiredEvidenceType(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE);
        execution.setOrderChecklistItem(item);

        var link = new MissionChecklistEvidence();
        link.setId("ev-1");
        link.setMissionId("mission-1");
        link.setMissionChecklistExecution(execution);
        link.setMediaAsset(captured);
        link.setAttachedAt(Instant.now());

        assertThat(evidencePolicy.attachable(captured))
                .as("a recovered DRONE_CAMERA asset must remain attachable")
                .isTrue();
        assertThat(evidencePolicy.ineligibilityReason(captured, false)).isNull();

        // One recovered asset satisfies one of the two distinct required images.
        assertThat(evidencePolicy.eligibleDistinctCount(List.of(link), "exec-1",
                com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE, false)).isEqualTo(1);
        assertThat(evidencePolicy.eligibleDistinctCount(List.of(link), "exec-1",
                com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE, false)).isLessThan(2);

        // Recovered media is already available, so it is also eligible at final approval.
        assertThat(evidencePolicy.ineligibilityReason(captured, true)).isNull();
    }

    @Test
    void mediaUploadedManuallyIsOfficialEvidenceOnceItPassesTheSameChecks() {
        // Manual upload is the fallback when the drone cannot upload; it is reviewed like any other media.
        captured.setSourceType("MANUAL_UPLOAD");
        assertThat(evidencePolicy.attachable(captured)).isTrue();
        assertThat(evidencePolicy.ineligibilityReason(captured, false)).isNotEqualTo("EVIDENCE_SOURCE_NOT_ELIGIBLE");
    }
}
