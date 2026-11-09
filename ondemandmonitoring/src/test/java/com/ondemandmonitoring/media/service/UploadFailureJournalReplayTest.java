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
 * Phase 3 acceptance tests for durable upload-failure journaling and replay:
 * 1. Concurrent and repeated delivery of the same eventId collapses onto one row.
 * 2. Backend processing commits but HTTP response is lost: replayed delivery is acknowledged.
 * 3. Backend outage followed by replay does not lose history.
 * 4. TRANSFER_ACK_LOST: storage inspection unavailable retains retryable state.
 * 5. TRANSFER_ACK_LOST: confirmed object exists proceeds through normal validation.
 * 6. TRANSFER_ACK_LOST: confirmed object absent is recorded as a genuine failure.
 * 7. Exactly four qualifying FAILED automatic transfers create one OPEN ticket.
 * 8. Late failure event cannot demote a recovered or already-approved asset.
 */
class UploadFailureJournalReplayTest {

    private static final String MEDIA_ID = "media-journal";

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
    private final Map<String, UploadFailureEvent> eventLedger = new HashMap<>();
    private final List<MediaUploadAttempt> attemptRows = new ArrayList<>();
    private final AtomicInteger taskInserts = new AtomicInteger(0);
    private ManualUploadTask storedTask;

    @BeforeEach
    void setUp() {
        eventLedger.clear();
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

        // Ledger: unique on eventKey, emulating PostgreSQL's uk_media_upload_failure_event.
        when(events.findByEventKey(any())).thenAnswer(c -> Optional.ofNullable(eventLedger.get(c.getArgument(0))));
        when(events.save(any(UploadFailureEvent.class))).thenAnswer(c -> {
            UploadFailureEvent e = c.getArgument(0);
            eventLedger.put(e.getEventKey(), e);
            return e;
        });
        when(events.saveAndFlush(any(UploadFailureEvent.class))).thenAnswer(c -> {
            UploadFailureEvent e = c.getArgument(0);
            if (eventLedger.containsKey(e.getEventKey())) {
                throw new DataIntegrityViolationException("duplicate event_key");
            }
            if (e.getId() == null) e.setId("evt-row-" + eventLedger.size());
            eventLedger.put(e.getEventKey(), e);
            return e;
        });

        when(attempts.findByIdAndMediaId(any(), eq(MEDIA_ID))).thenAnswer(c -> {
            String attemptId = c.getArgument(0);
            return attemptRows.stream().filter(a -> attemptId.equals(a.getId())).findFirst();
        });
        when(attempts.countByMediaIdAndManualAttemptFalseAndStatus(eq(MEDIA_ID), eq(UploadAttemptStatus.FAILED)))
                .thenAnswer(c -> attemptRows.stream()
                        .filter(a -> !a.isManualAttempt() && a.getStatus() == UploadAttemptStatus.FAILED)
                        .count());
        when(attempts.save(any(MediaUploadAttempt.class))).thenAnswer(c -> c.getArgument(0));

        when(manualTasks.findByMediaId(MEDIA_ID)).thenAnswer(c -> Optional.ofNullable(storedTask));
        when(manualTasks.save(any(ManualUploadTask.class))).thenAnswer(c -> {
            ManualUploadTask t = c.getArgument(0);
            if (t.getId() == null) t.setId("task-" + taskInserts.incrementAndGet());
            storedTask = t;
            return t;
        });
    }

    private MediaUploadAttempt pendingAttempt(int number) {
        MediaUploadAttempt a = new MediaUploadAttempt();
        a.setId("attempt-" + number);
        a.setMedia(captured);
        a.setAttemptNumber(number);
        a.setStatus(UploadAttemptStatus.PENDING);
        a.setStorageKey("staging/mission-1/device-1/" + MEDIA_ID + "/" + number + ".jpg");
        a.setManualAttempt(false);
        a.setExpiresAt(Instant.now().plusSeconds(900));
        attemptRows.add(a);
        return a;
    }

    private ReportUploadFailureRequest failureRequest(String eventId, int attemptNumber) {
        return ReportUploadFailureRequest.builder()
                .eventId(eventId)
                .category(UploadFailureCategory.TRANSFER_FAILED)
                .missionId("mission-1")
                .mediaId(MEDIA_ID)
                .attemptId("attempt-" + attemptNumber)
                .attemptNumber(attemptNumber)
                .localMediaId("capture-1")
                .occurredAt(Instant.now())
                .code("TRANSFER_FAILED")
                .message("S3 PUT failed #" + attemptNumber)
                .build();
    }

    // ---------- 1 & 2. Concurrent / duplicate delivery of same eventId ----------

    @Test
    void duplicateDeliveryOfSameEventIdIsAcknowledgedWithoutDoubleCounting() {
        pendingAttempt(1);
        var req = failureRequest("evt-1", 1);

        var first = service.ingest(req);
        var second = service.ingest(req);

        assertThat(first.isDurablyAccepted()).isTrue();
        assertThat(first.isDuplicate()).isFalse();
        // Second delivery acknowledges duplicate; client may safely delete its journal entry.
        assertThat(second.isDurablyAccepted()).isTrue();
        assertThat(second.isDuplicate()).isTrue();

        // Exactly one ledger row, exactly one FAILED attempt.
        assertThat(eventLedger).hasSize(1);
        assertThat(attemptRows.get(0).getStatus()).isEqualTo(UploadAttemptStatus.FAILED);
        assertThat(attemptRows.stream().filter(a -> a.getStatus() == UploadAttemptStatus.FAILED).count())
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveryLosesInsertRaceAndAcksCleanly() {
        pendingAttempt(1);
        var req = failureRequest("evt-concurrent", 1);

        // Pre-commit an existing row to emulate a concurrent transaction winning the race.
        UploadFailureEvent precommitted = new UploadFailureEvent();
        precommitted.setId("row-pre");
        precommitted.setEventKey("upload-failure:evt-concurrent");
        precommitted.setMissionId("mission-1");
        precommitted.setMediaId(MEDIA_ID);
        precommitted.setAttemptId("attempt-1");
        precommitted.setAttemptNumber(1);
        precommitted.setCategory(UploadFailureCategory.TRANSFER_FAILED);
        precommitted.setOccurredAt(Instant.now());
        precommitted.setProcessedAt(Instant.now());
        eventLedger.put(precommitted.getEventKey(), precommitted);

        var response = service.ingest(req);

        assertThat(response.isDurablyAccepted()).isTrue();
        assertThat(response.isDuplicate()).isTrue();
        assertThat(eventLedger).hasSize(1);
    }

    // ---------- 3. Fourth qualifying failure creates exactly one OPEN ticket ----------

    @Test
    void exactlyFourQualifyingFailuresCreateOneOpenTicketAcrossReplays() {
        for (int i = 1; i <= 3; i++) {
            pendingAttempt(i);
            var ack = service.ingest(failureRequest("evt-" + i, i));
            assertThat(ack.getStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
            assertThat(taskInserts.get()).isZero();
        }

        // Fourth failure: escalates.
        pendingAttempt(4);
        var fourth = service.ingest(failureRequest("evt-4", 4));

        assertThat(fourth.getStatus()).isEqualTo(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        assertThat(taskInserts.get()).isEqualTo(1);
        assertThat(storedTask.getStatus()).isEqualTo(ManualUploadTaskStatus.OPEN);
        assertThat(storedTask.getMedia().getId()).isEqualTo(MEDIA_ID);

        // Replaying all four events from the client journal does NOT create more tickets.
        for (int i = 1; i <= 4; i++) {
            var replay = service.ingest(failureRequest("evt-" + i, i));
            assertThat(replay.isDuplicate()).isTrue();
        }
        assertThat(taskInserts.get()).isEqualTo(1);
    }

    // ---------- 4-6. TRANSFER_ACK_LOST handling ----------

    @Test
    void transferAckLostWithConfirmedObjectDoesNotCountAsAFailedTransfer() {
        MediaUploadAttempt a = pendingAttempt(1);
        when(storage.inspect("test-bucket", a.getStorageKey())).thenReturn(
                new IMediaObjectStorage.StoredObjectInfo(100L, "image/jpeg", Map.of()));

        var req = failureRequest("evt-ack-1", 1);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        var ack = service.ingest(req);

        // Object is present: handed to validation; never marked FAILED.
        assertThat(ack.isDurablyAccepted()).isTrue();
        assertThat(ack.getStatus()).isEqualTo(MediaStatus.VALIDATING);
        assertThat(a.getStatus()).isNotEqualTo(UploadAttemptStatus.FAILED);
        assertThat(attemptRows.stream().filter(row -> row.getStatus() == UploadAttemptStatus.FAILED).count())
                .isZero();
        assertThat(taskInserts.get()).isZero();
    }

    @Test
    void transferAckLostWithConfirmedAbsentObjectIsRecordedAsAFailure() {
        MediaUploadAttempt a = pendingAttempt(1);
        when(storage.inspect("test-bucket", a.getStorageKey())).thenReturn(null);

        var req = failureRequest("evt-ack-absent", 1);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        var ack = service.ingest(req);

        assertThat(ack.isDurablyAccepted()).isTrue();
        assertThat(a.getStatus()).isEqualTo(UploadAttemptStatus.FAILED);
        assertThat(ack.getStatus()).isEqualTo(MediaStatus.RETRY_REQUIRED);
    }

    @Test
    void transferAckLostWhenStorageIsUninspectableStaysRetryableWithoutGuessing() {
        pendingAttempt(1);
        when(storage.inspect(any(), any())).thenThrow(new RuntimeException("S3 503 Service Unavailable"));

        var req = failureRequest("evt-ack-unavailable", 1);
        req.setCategory(UploadFailureCategory.TRANSFER_ACK_LOST);

        var ack = service.ingest(req);

        // Durably accepted so the client knows it was recorded, but retryLater signals
        // that processing is still pending inspection.
        assertThat(ack.isDurablyAccepted()).isTrue();
        assertThat(ack.isRetryLater()).isTrue();
        // The attempt was NOT marked FAILED: no guess was made.
        assertThat(attemptRows.get(0).getStatus()).isEqualTo(UploadAttemptStatus.PENDING);
    }

    // ---------- 8. Late event cannot demote recovered / approved media ----------

    @Test
    void lateEventCannotDemoteRecoveredOrApprovedMedia() {
        pendingAttempt(1);
        captured.setMediaStatus(MediaStatus.AVAILABLE);
        captured.setValidatedAt(Instant.now());

        var ack = service.ingest(failureRequest("evt-late", 1));

        assertThat(ack.isDurablyAccepted()).isTrue();
        // Status remains AVAILABLE; never overwritten to RETRY_REQUIRED or MANUAL_UPLOAD_REQUIRED.
        assertThat(captured.getMediaStatus()).isEqualTo(MediaStatus.AVAILABLE);
        assertThat(captured.getValidatedAt()).isNotNull();
    }
}