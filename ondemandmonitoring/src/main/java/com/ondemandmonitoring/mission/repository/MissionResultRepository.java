package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionResultRepository extends JpaRepository<MissionResult, String> {
    boolean existsByMediaFiles_Id(String mediaId);
    @Query("select r from MissionResult r where r.mission.id in :missionIds")
    java.util.List<MissionResult> findByMissionIds(@Param("missionIds") java.util.Collection<String> missionIds);
    @Query("select r.mission.id from MissionResult r where r.mission.id in :missionIds and r.approvalStatus = com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.APPROVED")
    java.util.Set<String> findApprovedMissionIds(@Param("missionIds") java.util.Set<String> missionIds);

    @Query("select r.mission.order.id from MissionResult r where r.id = :resultId")
    Optional<String> findOrderIdByResultId(@Param("resultId") String resultId);

    @Query("select r.mission.id from MissionResult r where r.id = :resultId")
    Optional<String> findMissionIdByResultId(@Param("resultId") String resultId);

    @Query("select r from MissionResult r where r.mission.id = :missionId")
    Optional<MissionResult> findByMissionId(@Param("missionId") String missionId);

    boolean existsByMissionId(String missionId);
    boolean existsByMissionIdAndApprovalStatus(String missionId, MissionResultApprovalStatus approvalStatus);

    Page<MissionResult> findByApprovalStatus(MissionResultApprovalStatus approvalStatus, Pageable pageable);
}
