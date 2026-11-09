package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MissionChecklistEvidenceRepository extends JpaRepository<MissionChecklistEvidence, String> {
    @Query("select e from MissionChecklistEvidence e join fetch e.mediaAsset join fetch e.missionChecklistExecution where e.missionId = :missionId and e.detachedAt is null order by e.attachedAt, e.id")
    List<MissionChecklistEvidence> findActiveByMissionId(String missionId);
    Optional<MissionChecklistEvidence> findByMissionChecklistExecution_IdAndMediaAsset_IdAndDetachedAtIsNull(String executionId, String mediaId);
    boolean existsByMediaAsset_Id(String mediaId);
    boolean existsByMediaAsset_IdAndDetachedAtIsNull(String mediaId);
}
