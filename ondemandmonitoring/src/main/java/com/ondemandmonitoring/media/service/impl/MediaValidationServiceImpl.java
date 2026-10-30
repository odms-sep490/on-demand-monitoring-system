package com.ondemandmonitoring.media.service.impl;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;

import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.IMediaValidationService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IMediaAuditService;
import com.ondemandmonitoring.media.service.IMediaObjectVerificationService;
import com.ondemandmonitoring.media.policy.MediaUploadPolicy;
import com.ondemandmonitoring.media.enums.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaValidationServiceImpl implements IMediaValidationService {

    MediaUploadAttemptRepository attempts;
    MediaAssetRepository media;
    StorageEventInboxRepository inbox;
    ManualUploadTaskRepository manualTasks;
    ManualUploadTaskServiceImpl manualTaskService;
    IMediaAuditService auditService;
    MediaUploadPolicy policy;
    IMediaObjectStorage storage;
    IMediaObjectVerificationService verification;
    IMissionMediaEvidenceGuard missionLock;

    @Override
    @Transactional
    public void processObjectCreated(String bucket, String key, String eventIdentity) {
        String eventKey = sha256((bucket + "\n" + key + "\n" + eventIdentity).getBytes());

        if (inbox.existsByEventKey(eventKey)) {
            return;
        }

        if (!storage.bucket().equals(bucket)) {
            return;
        }
        Optional<String> mediaId = attempts.findMediaIdByStorageKey(key);
        if (mediaId.isEmpty()) {
            log.info("Ignoring unrelated storage event bucket={} key={}", bucket, key);
            return;
        }
        // Lock aggregate first, then attempt; upload callbacks use the same order.
        missionLock.lock(mediaId.get());
        MediaAsset captured = media.findByIdForUpdate(mediaId.get()).orElseThrow();
        Optional<MediaUploadAttempt> found = attempts.findByStorageKeyForUpdate(key);

        if (found.isEmpty()) {
            log.info("Ignoring unrelated storage event bucket={} key={}", bucket, key);
            return;
        }

        MediaUploadAttempt attempt = found.get();
        if (captured.getStorageStatus() == MediaStorageStatus.DELETED
                || captured.getStorageStatus() == MediaStorageStatus.DELETE_PENDING
                || "ORPHAN_CLEANUP".equals(attempt.getFailureCode())) {
            // A PUT started before expiry can arrive late. Never resurrect a removed object.
            storage.delete(bucket, key);
            recordEvent(eventKey, bucket, key);
            return;
        }
        // SQS and reconciliation can wait on the same row concurrently.
        if (inbox.existsByEventKey(eventKey)) {
            return;
        }
        boolean latest = attempts.findFirstByMediaIdOrderByAttemptNumberDesc(captured.getId())
                .map(current -> current.getId().equals(attempt.getId())).orElse(false);

        if (!latest || attempt.getStatus() == UploadAttemptStatus.FAILED) {
            recordEvent(eventKey, bucket, key);
            return;
        }

        if (attempt.getStatus() == UploadAttemptStatus.SUCCEEDED) {
            recordEvent(eventKey, bucket, key);
            return;
        }

        String failure = verification.verify(captured, key);

        if (failure != null) {
            attempt.setStatus(UploadAttemptStatus.FAILED);
            attempt.setFailureCode(MediaFailureCode.STORAGE_VALIDATION_FAILED.name());
            attempt.setFailureMessage(failure);
            attempt.setCompletedAt(Instant.now());
            attempts.save(attempt);
            captured.setValidationError(failure);
            // A manual attempt always escalates. An automatic attempt escalates only once the
            // explicit tally of FAILED non-manual attempts is exhausted; prepares and abandoned
            // retries stay PENDING and can never contribute.
            if (policy.failureStatus(attempt.isManualAttempt(), failedAutomaticAttempts(captured.getId()))
                    == MediaStatus.MANUAL_UPLOAD_REQUIRED) {
                requireManualUpload(captured, failure);
            } else {
                captured.setMediaStatus(MediaStatus.RETRY_REQUIRED);
            }
            media.save(captured);
            audit(captured, attempt, MediaAuditAction.VALIDATION_FAILED, failure);
            recordEvent(eventKey, bucket, key);
            return;
        }

        String finalKey = key.replaceFirst("(^|/)staging/", "$1final/");

        if (finalKey.equals(key)) {
            throw new IllegalStateException("Attempt key is outside staging prefix");
        }

        storage.copy(bucket, key, finalKey);
        Instant now = Instant.now();
        captured.setS3Key(finalKey);
        captured.setS3Url("s3://" + bucket + "/" + finalKey);
        // Technical validation makes the media available to the inspector immediately.
        // Manager approval happens once for the complete mission result, not per file.
        captured.setMediaStatus(MediaStatus.AVAILABLE);
        captured.setValidationError(null);
        captured.setValidatedAt(now);
        captured.setAvailableAt(now);
        attempt.setStatus(UploadAttemptStatus.SUCCEEDED);
        attempt.setCompletedAt(now);
        attempts.save(attempt);
        media.save(captured);
        manualTasks.findByMediaId(captured.getId()).ifPresent(task -> {
            task.setStatus(ManualUploadTaskStatus.RESOLVED);
            task.setResolvedAt(now);
            manualTasks.save(task);
        });

        audit(captured, attempt, MediaAuditAction.AVAILABLE, null);
        recordEvent(eventKey, bucket, key);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storage.deleteQuietly(bucket, key);
                }
            });
        }
    }

    private long failedAutomaticAttempts(String mediaId) {
        return attempts.countByMediaIdAndManualAttemptFalseAndStatus(
                mediaId, UploadAttemptStatus.FAILED);
    }

    private void requireManualUpload(MediaAsset captured, String reason) {
        captured.setMediaStatus(MediaStatus.MANUAL_UPLOAD_REQUIRED);
        // Shared with the transfer-failure path so both always produce a visible OPEN task.
        manualTaskService.requireOpen(captured, reason);
    }

    private void audit(MediaAsset captured, MediaUploadAttempt attempt,
                       MediaAuditAction action, String detail) {
        auditService.record(captured, attempt, "s3-event-consumer", action, detail);
    }

    private void recordEvent(String eventKey, String bucket, String key) {
        StorageEventInbox event = new StorageEventInbox();
        event.setEventKey(eventKey);
        event.setBucket(bucket);
        event.setObjectKey(key);
        event.setProcessedAt(Instant.now());
        inbox.save(event);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
