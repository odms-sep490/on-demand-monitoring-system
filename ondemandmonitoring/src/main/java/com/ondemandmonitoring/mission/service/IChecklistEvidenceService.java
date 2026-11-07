package com.ondemandmonitoring.mission.service;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.*;
import java.util.List;
public interface IChecklistEvidenceService {
    List<ChecklistEvidenceResponse> selectForCustomer(String missionId, String executionId, ChecklistCustomerSelectionRequest request);
    List<ChecklistEvidenceResponse> attach(String missionId, BatchChecklistEvidenceRequest request);
    void detach(String missionId, String executionId, String evidenceId, long expectedVersion, String reason);
    List<ChecklistEvidenceCandidate> candidates(String missionId, int page, int size);
    List<MissionChecklistEvidence> active(String missionId);
    ChecklistExecutionResponse enrich(ChecklistExecutionResponse response, MissionChecklistExecution execution, List<MissionChecklistEvidence> evidence);
    boolean readyForInspectorApproval(MissionChecklistExecution execution, List<MissionChecklistEvidence> evidence);
    boolean ready(MissionChecklistExecution execution, List<MissionChecklistEvidence> evidence, boolean finalApproval);
    boolean evidenceRequirementsSatisfied(MissionChecklistExecution execution, List<MissionChecklistEvidence> evidence, boolean finalApproval);
    String readinessFailure(MissionChecklistExecution execution, List<MissionChecklistEvidence> evidence, boolean finalApproval);
}
