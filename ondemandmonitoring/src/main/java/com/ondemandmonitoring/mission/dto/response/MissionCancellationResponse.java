package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus;
import java.time.Instant;

public record MissionCancellationResponse(String missionId, MissionStatus status,
        MissionResourcePrepareStatus resourcePrepareStatus, long executionPlanRevision,
        Instant cancelledAt, String cancelledBy, String cancellationReason) {}
