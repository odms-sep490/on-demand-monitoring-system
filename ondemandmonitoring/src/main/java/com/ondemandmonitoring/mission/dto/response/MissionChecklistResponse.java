package com.ondemandmonitoring.mission.dto.response;

import java.util.List;

public record MissionChecklistResponse(String missionId, boolean legacySnapshot,
        boolean readyForSubmission, List<ChecklistExecutionResponse> executions,
        boolean checklistEvidenceReady, boolean readyForMissionCompletion, boolean readyForFinalApproval,
        List<String> blockingReasons) {}
