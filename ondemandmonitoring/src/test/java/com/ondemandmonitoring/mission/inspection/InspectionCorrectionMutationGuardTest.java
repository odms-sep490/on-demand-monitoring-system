package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionMutationGuard;
import org.junit.jupiter.api.Test;

class InspectionCorrectionMutationGuardTest {
  InspectionCorrectionRepository corrections = mock(InspectionCorrectionRepository.class);
  InspectionCorrectionItemRepository items = mock(InspectionCorrectionItemRepository.class);
  InspectionCorrectionMutationGuard guard =
      new InspectionCorrectionMutationGuard(corrections, items);

  @Test
  void freezesDelegatedOriginalExecution() {
    when(items.isDelegated("e")).thenReturn(true);
    assertThatThrownBy(() -> guard.requireEditable("m", "e")).isInstanceOf(ApiException.class);
  }

  @Test
  void freezesAcceptedSupplementEvidence() {
    when(corrections.existsBySupplementalMission_IdAndStatus(
            "child", InspectionCorrectionStatus.RESOLVED))
        .thenReturn(true);
    assertThatThrownBy(() -> guard.requireEditable("child", "e")).isInstanceOf(ApiException.class);
  }

  @Test
  void unrelatedAcceptedItemsRemainEditableUntilNormalResultLock() {
    guard.requireEditable("m", "good");
  }

  @Test
  void reporterCannotChangeAlreadyAcceptedChecklistWhileOtherWorkIsReturned() {
    var correction = new com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection();
    correction.setId("c");
    correction.setStatus(InspectionCorrectionStatus.OPEN);
    correction.setMode(com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.DOSSIER_ADJUSTMENT);
    var execution = new com.ondemandmonitoring.mission.domain.MissionChecklistExecution();
    execution.setId("e");
    execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT);
    var item = new com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionItem();
    item.setExecution(execution);
    when(corrections.findByMission_IdOrderByRequestedAtDescIdDesc("m")).thenReturn(java.util.List.of(correction));
    when(items.findItems("c")).thenReturn(java.util.List.of(item));
    assertThatThrownBy(() -> guard.requireDossierEditable("m", "e")).isInstanceOf(ApiException.class);
    execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.NOT_ASSESSED);
    guard.requireDossierEditable("m", "e");
  }
}
