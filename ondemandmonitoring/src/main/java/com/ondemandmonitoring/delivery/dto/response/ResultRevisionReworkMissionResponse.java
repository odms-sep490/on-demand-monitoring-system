package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.mission.enums.MissionStatus;

public record ResultRevisionReworkMissionResponse(
        String caseId,
        String missionId,
        String missionCode,
        MissionStatus status,
        int checklistCount) {}
