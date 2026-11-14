package com.ondemandmonitoring.mission.inspection.service;

import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionDecision;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionRequest;
import com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse;
import java.util.List;

public interface IInspectionCorrectionService {
  List<InspectionCorrectionResponse> list(String missionId);

  InspectionCorrectionResponse request(String missionId, InspectionCorrectionRequest request);

  InspectionCorrectionResponse resubmit(
      String missionId, String correctionId, InspectionCorrectionDecision request);

  InspectionCorrectionResponse resolve(
      String missionId, String correctionId, InspectionCorrectionDecision request);

  InspectionCorrectionResponse returnToStaff(
      String missionId, String correctionId, InspectionCorrectionDecision request);

  void requireResolved(String missionId);
}
