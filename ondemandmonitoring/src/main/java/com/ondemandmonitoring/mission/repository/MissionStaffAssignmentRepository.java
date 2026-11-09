package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MissionStaffAssignmentRepository extends JpaRepository<MissionStaffAssignment, String> {

    boolean existsByMissionIdAndStaffId(String missionId, String staffId);

    Optional<MissionStaffAssignment> findByMissionIdAndIsCurrentTrue(String missionId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "staff")
    List<MissionStaffAssignment> findAllByMissionIdAndIsCurrentTrue(String missionId);

    Optional<MissionStaffAssignment> findByMissionIdAndStaffIdAndIsCurrentTrue(String missionId, String staffId);

    List<MissionStaffAssignment> findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
            String missionId,
            String staffId);

    List<MissionStaffAssignment> findAllByMissionIdAndAssignedRoleAndIsCurrentTrue(
            String missionId,
            MissionStaffRole assignedRole);

    List<MissionStaffAssignment> findAllByMissionIdAndStaffIdAndAssignedRoleAndIsCurrentTrueOrderByAssignedAtDesc(
            String missionId,
            String staffId,
            MissionStaffRole assignedRole);

    Optional<MissionStaffAssignment> findFirstByMissionIdOrderByAssignedAtDesc(String missionId);

    List<MissionStaffAssignment> findByMissionId(String missionId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "staff")
    List<MissionStaffAssignment> findAllByMissionIdInAndIsCurrentTrue(List<String> missionIds);

}
