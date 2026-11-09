package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ondemandmonitoring.media.service.impl.MediaUploadReconciliationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Upload-failure recovery is correctness-critical: an accepted-but-unapplied failure event must
 * never be stranded. It therefore must not be gated by the routine reconciliation switch.
 */
class MediaUploadFailureRecoveryJobTest {

    @Test
    void failureRecoveryHasItsOwnSwitchDefaultingToEnabled() {
        ConditionalOnProperty condition = MediaUploadFailureRecoveryJob.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).as("failure recovery must expose its own setting").isNotNull();
        assertThat(condition.name()).containsExactly("app.media.upload-failure-recovery.enabled");
        // matchIfMissing = true: the safe default is ON, so an unconfigured deployment recovers.
        assertThat(condition.matchIfMissing()).isTrue();
        assertThat(condition.havingValue()).isEqualTo("true");
    }

    @Test
    void routineReconciliationKeepsItsOwnIndependentSwitch() {
        ConditionalOnProperty condition = MediaUploadReconciliationJob.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.name()).containsExactly("app.media.reconciliation.enabled");
        assertThat(condition.matchIfMissing()).isTrue();
        // The two switches are genuinely separate properties, so disabling one cannot
        // disable the other.
        assertThat(condition.name()).doesNotContainAnyElementsOf(
                java.util.Arrays.asList(MediaUploadFailureRecoveryJob.class
                        .getAnnotation(ConditionalOnProperty.class).name()));
    }

    @Test
    void failureRecoveryJobDelegatesToPendingEventRetry() {
        var failureEvents = mock(IUploadFailureEventService.class);
        new MediaUploadFailureRecoveryJob(failureEvents).recoverPendingFailureEvents();
        verify(failureEvents).retryPending();
    }

    @Test
    void reconciliationJobRunsRoutineRecoveryOnly() {
        var reconciliation = mock(IMediaUploadReconciliationService.class);
        new MediaUploadReconciliationJob(reconciliation).recover();
        // Routine reconciliation runs, and failure-event recovery is no longer its concern.
        verify(reconciliation).reconcileUploadedObjects();
    }
}