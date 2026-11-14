package com.ondemandmonitoring.mission.inspection.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InspectionCorrectionMutationGuard {
  private final InspectionCorrectionRepository corrections;
  private final InspectionCorrectionItemRepository items;

  public void requireEditable(String missionId, String executionId) {
    if (items.isDelegated(executionId)
        || corrections.existsBySupplementalMission_IdAndStatus(
            missionId, InspectionCorrectionStatus.RESOLVED))
      throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
  }
}
