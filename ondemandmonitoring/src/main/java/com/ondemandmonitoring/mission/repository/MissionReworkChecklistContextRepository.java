package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionReworkChecklistContext;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionReworkChecklistContextRepository
        extends JpaRepository<MissionReworkChecklistContext, String> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"caseItem"})
    List<MissionReworkChecklistContext> findByMissionIdOrderByCreatedAtAsc(String missionId);
}
