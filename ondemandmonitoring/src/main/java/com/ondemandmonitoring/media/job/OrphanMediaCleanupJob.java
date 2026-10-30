package com.ondemandmonitoring.media.job;

import com.ondemandmonitoring.media.service.IMediaOrphanCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="app.media.orphan-cleanup", name="enabled", havingValue="true", matchIfMissing=true)
public class OrphanMediaCleanupJob {
    private final IMediaOrphanCleanupService cleanup;
    @Scheduled(fixedDelayString="${app.media.orphan-cleanup.fixed-delay-ms:60000}")
    public void run() { cleanup.cleanup(); }
}
