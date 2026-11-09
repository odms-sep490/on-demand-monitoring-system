package com.ondemandmonitoring.media.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = "app.media.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class MediaUploadReconciliationJob {
    IMediaUploadReconciliationService reconciliation;

    @Scheduled(fixedDelayString = "${app.media.reconciliation.interval-ms:60000}",
            initialDelayString = "${app.media.reconciliation.interval-ms:60000}")
    public void recover() {
        // Routine object reconciliation only. Durably accepted upload-failure events are
        // recovered by MediaUploadFailureRecoveryJob, which has its own switch so disabling
        // this operational job can never strand failure history.
        reconciliation.reconcileUploadedObjects();
    }
}
