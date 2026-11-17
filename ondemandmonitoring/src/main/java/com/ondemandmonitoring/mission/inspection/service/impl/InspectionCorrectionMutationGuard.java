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

  public void requireDossierEditable(String missionId, String executionId) {
    var active = corrections.findByMission_IdOrderByRequestedAtDescIdDesc(missionId).stream()
        .filter(c -> c.getStatus() != InspectionCorrectionStatus.RESOLVED).findFirst();
    if (active.isPresent() && (active.get().getStatus() != InspectionCorrectionStatus.OPEN
        || active.get().getMode() != com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.DOSSIER_ADJUSTMENT
        || items.findItems(active.get().getId()).stream().noneMatch(i ->
            i.getExecution().getId().equals(executionId)
                && i.getExecution().getAssessmentStatus() != com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT)))
      throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
    requireEditable(missionId, executionId);
  }

  public void requireEditable(String missionId, String executionId) {
    if (items.isDelegated(executionId)
        || corrections.existsBySupplementalMission_IdAndStatus(
            missionId, InspectionCorrectionStatus.RESOLVED))
      throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
  }
}
