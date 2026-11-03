package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionReworkChecklistContext;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionReworkChecklistContextRepository
        extends JpaRepository<MissionReworkChecklistContext, String> {
    List<MissionReworkChecklistContext> findByMissionIdOrderByCreatedAtAsc(String missionId);
}
