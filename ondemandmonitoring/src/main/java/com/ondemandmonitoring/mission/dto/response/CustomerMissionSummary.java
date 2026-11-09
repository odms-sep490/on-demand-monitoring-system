package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.MissionStatus;
import java.time.Instant;

/** Public whitelist: deliberately excludes operational/planning fields. */
public record CustomerMissionSummary(String missionId, String orderId, String missionCode,
        String scopeLabel, MissionStatus status, Instant scheduledStartAt, Instant scheduledEndAt,
        String replacesMissionId, boolean reportAvailable) {}
