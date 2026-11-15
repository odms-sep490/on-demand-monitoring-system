package com.ondemandmonitoring.mission.inspection.repository;

import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionCorrectionRepository
    extends JpaRepository<InspectionCorrection, String> {
  List<InspectionCorrection> findByMission_IdOrderByRequestedAtDescIdDesc(String missionId);

  Optional<InspectionCorrection> findByMission_IdAndClientRequestId(
      String missionId, String clientRequestId);

  boolean existsBySupplementalMission_IdAndStatus(
      String missionId, InspectionCorrectionStatus status);

  boolean existsByActiveMissionId(String missionId);
}
