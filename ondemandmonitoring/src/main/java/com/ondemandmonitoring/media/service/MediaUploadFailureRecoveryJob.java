package com.ondemandmonitoring.media.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers upload-failure events that were durably accepted but not yet applied.
 * <p>
 * This is deliberately NOT gated by {@code app.media.reconciliation.enabled}. That flag
 * disables routine object reconciliation, which is an operational convenience; failing to
 * process an accepted failure event silently strands failure history and can prevent the
 * fourth-failure escalation from ever happening. Recovery here is correctness-critical, so it
 * has its own switch ({@code app.media.upload-failure-recovery.enabled}, default true).
 * <p>
 * The interval reuses the reconciliation cadence unless overridden.
 */
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = "app.media.upload-failure-recovery.enabled",
        havingValue = "true", matchIfMissing = true)
public class MediaUploadFailureRecoveryJob {

    IUploadFailureEventService failureEvents;

    @Scheduled(fixedDelayString = "${app.media.upload-failure-recovery.interval-ms:60000}",
            initialDelayString = "${app.media.upload-failure-recovery.interval-ms:60000}")
    public void recoverPendingFailureEvents() {
        failureEvents.retryPending();
    }
}