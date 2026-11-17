package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionEvidenceSubmission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionEvidenceSubmissionRepository extends JpaRepository<MissionEvidenceSubmission, String> {
    Optional<MissionEvidenceSubmission> findFirstByMission_IdOrderByRevisionDesc(String missionId);
    Optional<MissionEvidenceSubmission> findByMission_IdAndCommandId(String missionId, String commandId);
    List<MissionEvidenceSubmission> findByMission_IdOrderByRevisionDesc(String missionId);
}
