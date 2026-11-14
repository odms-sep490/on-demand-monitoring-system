package com.ondemandmonitoring.mission.inspection.repository;

import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InspectionCorrectionItemRepository
    extends JpaRepository<InspectionCorrectionItem, String> {
  @Query(
      "select i from InspectionCorrectionItem i join fetch i.execution join fetch i.correction c"
          + " join fetch c.supplementalMission where c.mission.id=:missionId and"
          + " c.status=com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus.RESOLVED"
          + " order by c.resolvedAt, c.id, i.id")
  List<InspectionCorrectionItem> findResolvedDelegations(String missionId);

  @Query(
      "select count(i)>0 from InspectionCorrectionItem i where i.execution.id=:executionId and"
          + " i.correction.status=com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus.RESOLVED"
          + " and i.correction.supplementalMission is not null")
  boolean isDelegated(String executionId);

  @Query(
      "select i from InspectionCorrectionItem i join fetch i.execution where"
          + " i.correction.id=:correctionId order by i.id")
  List<InspectionCorrectionItem> findItems(String correctionId);
}
