package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionChecklistResponse;

public interface IMissionChecklistExecutionService {
    void requireMaterialized(Mission mission);
    MissionChecklistResponse getByMissionId(String missionId);
    ChecklistExecutionResponse update(String missionId, String executionId, ChecklistExecutionUpdateRequest request);
    void markMissionFailed(String missionId, String reason);
    boolean isReadyForSubmission(Mission mission);
    boolean isReadyForFinalApproval(Mission mission);
    boolean isReadyForInspectorApproval(Mission mission);
    void requireReadyForInspectorApproval(Mission mission);
    void requireReadyForSubmission(Mission mission);
    void requireReadyForFinalApproval(Mission mission);
}
