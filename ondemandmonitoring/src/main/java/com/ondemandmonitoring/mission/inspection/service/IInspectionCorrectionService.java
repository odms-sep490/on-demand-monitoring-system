package com.ondemandmonitoring.mission.inspection.service;

import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionDecision;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionRequest;
import com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse;
import java.util.List;

public interface IInspectionCorrectionService {
  List<InspectionCorrectionResponse> list(String missionId);

  com.ondemandmonitoring.mission.inspection.dto.response.SupplementInspectionResponse
      supplementResult(String missionId, String correctionId);

  InspectionCorrectionResponse request(String missionId, InspectionCorrectionRequest request);

  InspectionCorrectionResponse resubmit(
      String missionId, String correctionId, InspectionCorrectionDecision request);

  InspectionCorrectionResponse resolve(
      String missionId, String correctionId, InspectionCorrectionDecision request);

  InspectionCorrectionResponse returnToStaff(
      String missionId, String correctionId, InspectionCorrectionDecision request);

  InspectionCorrectionResponse requireFlight(String missionId, String id, InspectionCorrectionDecision decision);

  /** Called after an internal supplemental result has been inspector-approved. */
  void registerAcceptedSupplement(com.ondemandmonitoring.mission.domain.Mission mission);

  InspectionCorrectionResponse synchronizeSupplement(String missionId, String id);

  void reconcileChecklistReview(String missionId);

  void requireResolved(String missionId);
}
