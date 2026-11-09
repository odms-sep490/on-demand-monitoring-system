package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.enums.ManualUploadTaskStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.enums.UploadFailureCategory;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.impl.ManualUploadTaskServiceImpl;
import com.ondemandmonitoring.media.service.impl.UploadFailureEventServiceImpl;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Hardening: durable acceptance versus processing.
 * <p>
 * {@code durablyAccepted} means the Backend owns the event and every subsequent retry. The
 * client deletes its IndexedDB copy immediately, so an accepted-but-unprocessed event MUST be
 * completed by the Backend alone. These tests prove an interrupted or uninspectable event is
 * recovered by {@code retryPending()} rather than stranded.
 */
class UploadFailureEventRecoveryTest {

    private static final String MEDIA_ID = "media-recovery";

    private final UploadFailureEventRepository events = mock(UploadFailureEventRepository.class);
    private final MediaUploadAttemptRepository attempts = mock(MediaUploadAttemptRepository.class);
    private final MediaAssetRepository media = mock(MediaAssetRepository.class);
    private final ManualUploadTaskRepository manualTasks = mock(ManualUploadTaskRepository.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final IMissionMediaEvidenceGuard missionLock = mock(IMissionMediaEvidenceGuard.class);
    private final ManualUploadTaskServiceImpl manualTaskService = new ManualUploadTaskServiceImpl(manualTasks);
    private final MediaUploadPolicy policy = new MediaUploadPolicy();

    private UploadFailureEventServiceImpl service;
    private MediaAsset captured;
    private final Map<String, UploadFailureEvent> ledger = new HashMap<>();
    private final List<MediaUploadAttempt> attemptRows = new ArrayList<>();
    private final AtomicInteger taskInserts = new AtomicInteger(0);
    private ManualUploadTask storedTask;

    @BeforeEach
    void setUp() {
        ledger.clear();
        attemptRows.clear();
        taskInserts.set(0);
        storedTask = null;
        service = new UploadFailureEventServiceImpl(events, attempts, media, policy, storage,
                manualTaskService, missionLock);

        captured = new MediaAsset();
        captured.setId(MEDIA_ID);
        captured.setMissionId("mission-1");
        captured.setDeviceId("device-1");
        captured.setLocalMediaId("capture-1");
        captured.setOperatorId("operator-1");
        captured.setType("IMAGE");
        captured.setSourceType("DRONE_CAMERA");
        captured.setContentType("image/jpeg");
        captured.setFileSize(100L);
        captured.setChecksumSha256("a".repeat(64));
        captured.setOriginalFileName("capture.jpg");
        captured.setS3Bucket("test-bucket");
        captured.setMediaStatus(MediaStatus.UPLOAD_PENDING);

        when(media.findById(MEDIA_ID)).thenReturn(Optional.of(captured));
        when(media.save(any(MediaAsset.class))).thenAnswer(c -> c.getArgument(0));

        when(events.findByEventKey(any())).thenAnswer(c -> Optional.ofNullable(ledger.get(c.getArgument(0))));
        when(events.save(any(UploadFailureEvent.class))).thenAnswer(c -> {
            UploadFailureEvent e = c.getArgument(0);
            ledger.put(e.getEventKey(), e);
            return e;
        });
        when(events.saveAndFlush(any(UploadFailureEvent.class))).thenAnswer(c -> {
            UploadFailureEvent e = c.getArgument(0);
            if (ledger.containsKey(e.getEventKey())) {
                throw new DataIntegrityViolationException("duplicate event_key");
            }
            if (e.getId() == null) e.setId("row-" + ledger.size());
            ledger.put(e.getEventKey(), e);
            return e;
        });
        // Pending events are exactly the stored-but-unapplied ones.
        when(events.findByProcessedAtIsNullOrderByOccurredAtAsc()).thenAnswer(c ->
                ledger.values().stream().filter(e -> e.getProcessedAt() == null).toList());

        when(attempts.findByIdAndMediaId(any(), eq(MEDIA_ID))).thenAnswer(c -> {
            String id = c.getArgument(0);
            return attemptRows.stream().filter(a -> id.equals(a.getId())).findFirst();
        });
        when(attempts.countByMediaIdAndManualAttemptFalseAndStatus(eq(MEDIA_ID), eq(UploadAttemptStatus.FAILED)))
                .thenAnswer(c -> attemptRows.stream()
                        .filter(a -> !a.isManualAttempt() && a.getStatus() == UploadAttemptStatus.FAILED).count());
        when(attempts.save(any(MediaUploadAttempt.class))).thenAnswer(c -> c.getArgument(0));

        when(manualTasks.findByMediaId(MEDIA_ID)).thenAnswer(c -> Optional.ofNullable(storedTask));
        when(manualTasks.save(any(ManualUploadTask.class))).thenAnswer(c -> {
            ManualUploadTask t = c.getArgument(0);
            if (t.getId() == null) t.setId("task-" + taskInserts.incrementAndGet());
            storedTask = t;
            return t;
        });
    }

    private MediaUploadAttempt attempt(int n) {
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

    private ReportUploadFailureRequest request(String eventId, int n) {
        return ReportUploadFailureRequest.builder()
                .eventId(eventId).category(UploadFailureCategory.TRANSFER_FAILED)
                .missionId("mission-1").mediaId(MEDIA_ID).attemptId("attempt-" + n)
                .attemptNumber(n).localMediaId("capture-1").occurredAt(Instant.now())
                .code("TRANSFER_FAILED").message("S3 PUT failed #" + n).build();
    }

    private long failedCount() {
        return attemptRows.stream().filter(a -> a.getStatus() == UploadAttemptStatus.FAILED).count();
    }

    // ---------- 3. Stored then interrupted: eventually processed after recovery ----------

    @Test
    void eventAcceptedButUnprocessedIsCompletedByBackendRetryJob() {
        attempt(1);
        // Storage becomes inspectable only for the retry job, not for ingest.
        doThrow(new RuntimeException("S3 unavailable")).when(storage).inspect(any(), any());
        var req = request("evt-interrupted", 1);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        var ack = service.ingest(req);

        // Durably accepted: the client will delete its journal copy right now.
        assertThat(ack.isDurablyAccepted()).isTrue();
        assertThat(ack.isRetryLater()).isTrue();
        // ...yet the event is NOT processed, so nothing has been decided.
        UploadFailureEvent stored = ledger.get("upload-failure:evt-interrupted");
        assertThat(stored).isNotNull();
        assertThat(stored.isProcessed()).isFalse();
        assertThat(failedCount()).as("no transfer may be invented").isZero();

        // Storage recovers; the Backend job finishes the work the client no longer owns.
        doReturn(null).when(storage).inspect(any(), any()); // overrides the outage stub
        service.retryPending();

        assertThat(ledger.get("upload-failure:evt-interrupted").isProcessed()).isTrue();
        assertThat(failedCount()).isEqualTo(1);
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
    }

    @Test
    void recoveredAckLostEventWithObjectPresentCompletesAsValidationNotFailure() {
        attempt(1);
        doThrow(new RuntimeException("S3 unavailable")).when(storage).inspect(any(), any());
        var req = request("evt-ack-present", 1);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);
        service.ingest(req);

        doReturn(new IMediaObjectStorage.StoredObjectInfo(100L, "image/jpeg", Map.of()))
                .when(storage).inspect(any(), any());
        service.retryPending();

        assertThat(failedCount()).as("a confirmed object is never a failed transfer").isZero();
        assertThat(ledger.get("upload-failure:evt-ack-present").getProcessingOutcome())
                .isEqualTo(UploadFailureEventServiceImpl.OUTCOME_OBJECT_PRESENT);
    }

    // ---------- 4. Duplicate arriving while the original row is still pending ----------

    @Test
    void duplicateDeliveryResumesProcessingOfAPendingEventInsteadOfStrandingIt() {
        attempt(1);
        doThrow(new RuntimeException("S3 unavailable")).when(storage).inspect(any(), any());
        var req = request("evt-dup-pending", 1);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        service.ingest(req);
        assertThat(ledger.get("upload-failure:evt-dup-pending").isProcessed()).isFalse();

        // The same event is redelivered while still pending. It must resume, not just ack.
        doReturn(null).when(storage).inspect(any(), any());
        var duplicate = service.ingest(req);

        assertThat(duplicate.isDuplicate()).isTrue();
        assertThat(duplicate.isDurablyAccepted()).isTrue();
        // Resumed and finished, so the row is no longer stranded.
        assertThat(ledger.get("upload-failure:evt-dup-pending").isProcessed()).isTrue();
        assertThat(failedCount()).isEqualTo(1);
    }

    @Test
    void duplicateOfAnAlreadyProcessedEventIsAcknowledgedWithoutReapplying() {
        attempt(1);
        var req = request("evt-processed", 1);

        service.ingest(req);
        assertThat(ledger.get("upload-failure:evt-processed").isProcessed()).isTrue();

        var duplicate = service.ingest(req);

        assertThat(duplicate.isDuplicate()).isTrue();
        assertThat(duplicate.isDurablyAccepted()).isTrue();
        // Re-delivery must not double-count.
        assertThat(failedCount()).isEqualTo(1);
        assertThat(taskInserts.get()).isZero();
    }

    // ---------- 5 & 6. Accepted-then-client-deleted is never lost; retries are safe ----------

    @Test
    void eventSurvivesClientDeletionAndRetryJobCompletesItExactlyOnce() {
        // Three prior qualifying failures; the fourth attempt is the uninspectable event.
        for (int i = 1; i <= 4; i++) attempt(i);
        for (int i = 1; i <= 3; i++) service.ingest(request("evt-prior-" + i, i));
        doThrow(new RuntimeException("S3 unavailable")).when(storage).inspect(any(), any());
        var req = request("evt-client-deleted", 4);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        // Backend accepts and the client deletes its IndexedDB copy immediately.
        var ack = service.ingest(req);
        assertThat(ack.isDurablyAccepted()).isTrue();
        assertThat(ack.isRetryLater()).isTrue();
        assertThat(ledger.get("upload-failure:evt-client-deleted").isProcessed()).isFalse();

        // Storage recovers. The Backend must finish the escalation on its own.
        doReturn(null).when(storage).inspect(any(), any());
        service.retryPending();

        // Three prior failures plus this one is the fourth qualifying failure.
        assertThat(failedCount()).isEqualTo(4);
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
    }

    @Test
    void repeatedRetryJobRunsDoNotDuplicateFailedCountsOrTickets() {
        for (int i = 1; i <= 4; i++) attempt(i);
        for (int i = 1; i <= 3; i++) service.ingest(request("evt-prior-" + i, i));
        doThrow(new RuntimeException("S3 unavailable")).when(storage).inspect(any(), any());
        var req = request("evt-repeated", 4);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        service.ingest(req);
        doReturn(null).when(storage).inspect(any(), any());
        service.retryPending();
        service.retryPending();
        service.retryPending();

        assertThat(failedCount()).isEqualTo(4);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(ledger.get("upload-failure:evt-repeated").isProcessed()).isTrue();
    }

    @Test
    void jobRetriesAreBoundedToPendingRowsOnly() {
        for (int i = 1; i <= 3; i++) {
            attempt(i);
            service.ingest(request("evt-" + i, i));
        }
        // All processed: a further job pass must be a no-op.
        service.retryPending();

        assertThat(failedCount()).isEqualTo(3);
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
        assertThat(taskInserts.get()).isZero();
        assertThat(ledger.values()).allMatch(UploadFailureEvent::isProcessed);
    }
}
