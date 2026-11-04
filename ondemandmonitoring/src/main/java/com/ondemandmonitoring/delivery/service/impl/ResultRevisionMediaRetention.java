package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository;
import com.ondemandmonitoring.media.infrastructure.s3.ResultRetentionProperties;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResultRevisionMediaRetention {
    private final ResultRevisionMediaHoldRepository holds;
    private final ResultRetentionProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public Instant release(String caseId, Instant closedAt) {
        Instant protectedUntil = closedAt.plus(Duration.ofDays(properties.getRevisionGraceDays()));
        holds.releaseCase(caseId, protectedUntil);
        return protectedUntil;
    }
}
