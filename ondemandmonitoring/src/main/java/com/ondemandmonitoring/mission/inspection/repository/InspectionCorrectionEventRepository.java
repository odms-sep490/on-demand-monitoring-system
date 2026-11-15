package com.ondemandmonitoring.mission.inspection.repository;

import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionCorrectionEventRepository
    extends JpaRepository<InspectionCorrectionEvent, String> {
  long countByCorrection_Id(String correctionId);

  List<InspectionCorrectionEvent> findByCorrection_IdOrderBySequenceNumberAsc(String correctionId);
}
