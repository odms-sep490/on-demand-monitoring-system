package com.ondemandmonitoring.mission.inspection.service;

import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.inspection.dto.request.InternalCorrectionMissionRequest;

public interface IInternalInspectionMissionService {
  MissionResponse create(
      String missionId, String correctionId, InternalCorrectionMissionRequest request);

  MissionResponse finalizePlan(
      String missionId, String correctionId, ExecutionPlanFinalizeRequest request);
}
