package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionStatus;
import java.time.Instant;

public record MissionCancellationResponse(String missionId, MissionStatus status,
        long executionPlanRevision, Instant cancelledAt, String cancelledBy,
        String cancellationReason) {}
