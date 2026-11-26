package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MissionStaffAssignmentRepository extends JpaRepository<MissionStaffAssignment, String> {

    /** Staff's current, non-declined assignments on missions that have actually started but are not completed yet. */
    @org.springframework.data.jpa.repository.Query("""
            select a from MissionStaffAssignment a join fetch a.mission m
            where a.staff.id = :staffId
              and a.isCurrent = true
              and a.releasedAt is null
              and a.responseStatus <> com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus.REJECTED
              and m.actualStartAt is not null
              and m.completedAt is null
              and m.status = com.ondemandmonitoring.mission.enums.MissionStatus.IN_PROGRESS
            """)
    List<MissionStaffAssignment> findRunningMissionAssignmentsForStaff(
            @org.springframework.data.repository.query.Param("staffId") String staffId);

    boolean existsByMissionIdAndStaffId(String missionId, String staffId);

    boolean existsByMissionIdAndStaffIdAndIsCurrentTrueAndReleasedAtIsNull(
            String missionId, String staffId);

    boolean existsByMissionIdAndStaffIdAndResponseStatus(
            String missionId, String staffId, MissionStaffAssignmentStatus responseStatus);

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

    @EntityGraph(attributePaths = {"mission", "mission.order", "staff"})
    List<MissionStaffAssignment> findByResponseStatusAndIsCurrentTrueAndReleasedAtIsNullOrderByRespondedAtDesc(
            MissionStaffAssignmentStatus responseStatus, Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "staff")
    List<MissionStaffAssignment> findAllByMissionIdInAndIsCurrentTrue(List<String> missionIds);

}
