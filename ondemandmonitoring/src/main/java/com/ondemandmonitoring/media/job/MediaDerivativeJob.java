package com.ondemandmonitoring.media.job;

import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.media.derivatives", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MediaDerivativeJob {
    private final MediaDerivativeService derivatives;

    @Scheduled(fixedDelayString = "${app.media.derivatives.interval-ms:15000}",
            initialDelayString = "${app.media.derivatives.initial-delay-ms:10000}")
    public void processPending() {
        int processed = derivatives.processBatch();
        if (processed > 0) log.info("Processed media derivatives count={}", processed);
    }
}
