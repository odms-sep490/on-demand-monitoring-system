package com.ondemandmonitoring.media.cleanup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ObjectCleanupJob {
    private final ObjectCleanupWorker worker;

    @Scheduled(
            fixedDelayString = "${app.storage-cleanup.poll-delay-ms:5000}",
            initialDelayString = "${app.storage-cleanup.initial-delay-ms:10000}")
    public void run() {
        try {
            worker.poll();
        } catch (RuntimeException failure) {
            log.warn(
                    "Object cleanup dispatcher unavailable; failure={}",
                    failure.getClass().getSimpleName());
        }
    }
}
