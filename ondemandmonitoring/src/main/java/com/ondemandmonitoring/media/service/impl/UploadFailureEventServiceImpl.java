package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.domain.MediaUploadAttempt;
import com.ondemandmonitoring.media.enums.UploadAttemptStatus;
import com.ondemandmonitoring.media.domain.UploadFailureEvent;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.dto.response.UploadFailureAckResponse;
import com.ondemandmonitoring.media.enums.UploadFailureCategory;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository;
import com.ondemandmonitoring.media.repository.UploadFailureEventRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IUploadFailureEventService;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Durable ingestion of client-journaled upload-failure events.
 * <p>
 * Idempotency comes from the {@code uk_media_upload_failure_event} unique constraint rather
 * than a check-then-insert race: the insert is attempted first, and a concurrent duplicate
 * loses the race and re-reads the committed row. Replay order and concurrency therefore cannot
 * double-count a failed transfer.
 * <p>
 * {@link UploadFailureCategory#TRANSFER_ACK_LOST} never marks an attempt FAILED. The exact
 * object is re-inspected through the existing storage inspection path; when storage cannot be
 * inspected the event deliberately stays unprocessed so a later retry decides, rather than the
 * Backend guessing the transfer outcome.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UploadFailureEventServiceImpl implements IUploadFailureEventService {

    /** Namespaced so it can never collide with the S3 storage-event inbox keys. */
    public static final String EVENT_KEY_PREFIX = "upload-failure:";
    static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_OBJECT_PRESENT = "OBJECT_PRESENT";
    static final String OUTCOME_ATTEMPT_MISSING = "ATTEMPT_MISSING";

    UploadFailureEventRepository events;
    MediaUploadAttemptRepository attempts;
    MediaAssetRepository media;
    MediaUploadPolicy policy;
    IMediaObjectStorage storage;
    ManualUploadTaskServiceImpl manualTaskService;
    IMissionMediaEvidenceGuard missionLock;

    @Override
    @Transactional
    public UploadFailureAckResponse ingest(ReportUploadFailureRequest request) {
        String eventKey = eventKey(request.getEventId());
        UploadFailureCategory category = request.getCategory() == null
                ? UploadFailureCategory.TRANSFER_FAILED : request.getCategory();

        // Record the event durably BEFORE touching any transfer state, so an interrupted
        // replay can never lose failure history.
        UploadFailureEvent accepted = accept(eventKey, request, category);
        if (accepted != null) return applyRecorded(accepted, request, category);

        // The key already exists (earlier or concurrent delivery). Re-read it and RESUME
        // processing when it is still pending: acknowledging a stored-but-unapplied row
        // without finishing it would strand the event, because the client is authorised to
        // delete its journal copy as soon as it sees `durablyAccepted`.
        UploadFailureEvent existing = events.findByEventKey(eventKey).orElse(null);
        if (existing == null) {
            // Lost the insert race and the winner has not committed yet; the client retries.
            return UploadFailureAckResponse.builder()
                    .durablyAccepted(false).retryLater(true).eventKey(eventKey)
                    .mediaId(request.getMediaId()).build();
        }
        UploadFailureAckResponse response = applyRecorded(existing, request, existing.getCategory());
        response.setDuplicate(true);
        return response;
    }

    private UploadFailureEvent accept(String eventKey, ReportUploadFailureRequest request,
            UploadFailureCategory category) {
        UploadFailureEvent existing = events.findByEventKey(eventKey).orElse(null);
        if (existing != null) return null;
        UploadFailureEvent event = new UploadFailureEvent();
        event.setEventKey(eventKey);
        event.setMissionId(request.getMissionId());
        event.setMediaId(request.getMediaId());
        event.setAttemptId(request.getAttemptId());
        event.setAttemptNumber(request.getAttemptNumber());
        event.setCategory(category);
        event.setOccurredAt(request.getOccurredAt() == null ? Instant.now() : request.getOccurredAt());
        event.setFailureCode(request.getCode());
        event.setFailureMessage(request.getMessage());
        try {
            return events.saveAndFlush(event);
        } catch (DataIntegrityViolationException lostInsertRace) {
            // A concurrent delivery committed this key first; its row is the durable record.
            return null;
        }
    }

    private UploadFailureAckResponse applyRecorded(UploadFailureEvent event,
            ReportUploadFailureRequest request, UploadFailureCategory category) {
        if (event.isProcessed()) {
            // Already applied by an earlier delivery: an idempotent, successful replay.
            return UploadFailureAckResponse.builder()
                    .durablyAccepted(true).duplicate(true)
                    .eventKey(event.getEventKey()).mediaId(event.getMediaId())
                    .category(event.getCategory()).retryLater(false)
                    .build();
        }
        return category == UploadFailureCategory.TRANSFER_ACK_LOST
                ? applyAckLost(event)
                : applyTransferFailure(event);
    }

    /**
     * The bytes may already be in storage, so this never invents a failed transfer. A confirmed
     * object is handed to the existing validation workflow; an uninspectable object leaves the
     * event retryable.
     */
    private UploadFailureAckResponse applyAckLost(UploadFailureEvent event) {
        MediaUploadAttempt attempt = attemptOf(event);
        if (attempt == null) return attemptMissing(event);
        MediaAsset captured = media.findById(event.getMediaId()).orElse(null);
        IMediaObjectStorage.StoredObjectInfo object;
        try {
            object = storage.inspect(captured == null ? null : captured.getS3Bucket(), attempt.getStorageKey());
        } catch (RuntimeException storageUnavailable) {
            // Retain a retryable state rather than guessing the transfer outcome.
            return UploadFailureAckResponse.builder()
                    .durablyAccepted(true).retryLater(true)
                    .eventKey(event.getEventKey()).mediaId(event.getMediaId())
                    .category(event.getCategory()).build();
        }
        if (object == null) {
            // The object is definitively absent, so this really was a failed transfer.
            return applyTransferFailure(event);
        }
        event.setProcessingOutcome(OUTCOME_OBJECT_PRESENT);
        event.setProcessedAt(Instant.now());
        events.save(event);
        // The object exists: let the existing validation/reconciliation workflow continue it.
        return UploadFailureAckResponse.builder()
                .durablyAccepted(true).duplicate(false)
                .eventKey(event.getEventKey()).mediaId(event.getMediaId())
                .category(event.getCategory()).status(MediaStatus.VALIDATING).retryLater(false)
                .build();
    }

    /**
     * Records a genuine failed automatic transfer, escalating only after the Backend durably
     * knows about the fourth qualifying failure.
     */
    private UploadFailureAckResponse applyTransferFailure(UploadFailureEvent event) {
        missionLock.lock(event.getMediaId());
        MediaUploadAttempt attempt = attemptOf(event);
        if (attempt == null) return attemptMissing(event);

        if (attempt.getStatus() != UploadAttemptStatus.FAILED) {
            attempt.setStatus(UploadAttemptStatus.FAILED);
            attempt.setFailureCode(event.getFailureCode());
            attempt.setFailureMessage(event.getFailureMessage());
            attempt.setCompletedAt(Instant.now());
            attempts.save(attempt);
        }
        event.setProcessingOutcome(OUTCOME_APPLIED);
        event.setProcessedAt(Instant.now());
        events.save(event);

        MediaAsset captured = media.findById(event.getMediaId()).orElse(null);
        if (captured == null) return accepted(event);
        if (captured.getMediaStatus() == MediaStatus.PENDING_MANAGER_APPROVAL
                || captured.getMediaStatus() == MediaStatus.AVAILABLE) {
            // Already recovered or approved: never demote it because of a late event.
            return acceptedWithStatus(event, captured.getMediaStatus());
        }
        captured.setValidationError(event.getFailureMessage());
        if (policy.failedAutomaticAttemptsExhausted(failedCount(captured.getId()))) {
            captured.setMediaStatus(MediaStatus.MANUAL_UPLOAD_REQUIRED);
            manualTaskService.requireOpen(captured, event.getFailureMessage());
        } else {
            captured.setMediaStatus(MediaStatus.RETRY_REQUIRED);
        }
        media.save(captured);
        return acceptedWithStatus(event, captured.getMediaStatus());
    }

    @Override
    public UploadFailureAckResponse retryPending() {
        UploadFailureAckResponse last = null;
        // Isolate each event: one transient storage failure must not abort the sweep and
        // strand every later pending event. The failed row stays unprocessed and is retried
        // on the next pass.
        for (UploadFailureEvent event : events.findByProcessedAtIsNullOrderByOccurredAtAsc()) {
            try {
                last = applyRecorded(event, null, event.getCategory());
            } catch (RuntimeException transientFailure) {
                last = UploadFailureAckResponse.builder()
                        .durablyAccepted(true).retryLater(true)
                        .eventKey(event.getEventKey()).mediaId(event.getMediaId())
                        .category(event.getCategory()).build();
            }
        }
        return last;
    }

    private UploadFailureAckResponse attemptMissing(UploadFailureEvent event) {
        event.setProcessingOutcome(OUTCOME_ATTEMPT_MISSING);
        event.setProcessedAt(Instant.now());
        events.save(event);
        return accepted(event);
    }

    private UploadFailureAckResponse accepted(UploadFailureEvent event) {
        return acceptedWithStatus(event, null);
    }

    private UploadFailureAckResponse acceptedWithStatus(UploadFailureEvent event, MediaStatus status) {
        return UploadFailureAckResponse.builder()
                .durablyAccepted(true).duplicate(false)
                .eventKey(event.getEventKey()).mediaId(event.getMediaId())
                .category(event.getCategory()).status(status).retryLater(false)
                .build();
    }

    private MediaUploadAttempt attemptOf(UploadFailureEvent event) {
        return attempts.findByIdAndMediaId(event.getAttemptId(), event.getMediaId()).orElse(null);
    }

    private long failedCount(String mediaId) {
        return attempts.countByMediaIdAndManualAttemptFalseAndStatus(mediaId, UploadAttemptStatus.FAILED);
    }

    private String eventKey(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "eventId is required for a journaled upload failure report");
        }
        return EVENT_KEY_PREFIX + eventId;
    }
}