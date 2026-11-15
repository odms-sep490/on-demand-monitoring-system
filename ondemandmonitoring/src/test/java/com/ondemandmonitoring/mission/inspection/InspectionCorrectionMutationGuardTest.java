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
}
