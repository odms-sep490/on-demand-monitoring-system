package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentSelectionResponse;

public interface IMissionChecklistAssignmentService {
    ChecklistAssignmentSelectionResponse get(String missionId);
    ChecklistAssignmentSelectionResponse initializeInternalCorrectionDraft(String sourceMissionId, String missionId, ChecklistAssignmentSelectionRequest request);
    ChecklistAssignmentSelectionResponse replace(String missionId, ChecklistAssignmentSelectionRequest request);
}
