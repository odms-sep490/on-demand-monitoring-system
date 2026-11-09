package com.ondemandmonitoring.mission.dto.response;

import java.time.Instant;

/** Only business-approved, customer-facing content. No internal notes or linked media. */
public record CustomerPublishedMissionResult(String id, String summary, Instant startedAt,
        Instant endedAt, Instant completedAt, Long durationSeconds) {}
