package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface MissionDeviceAssignmentRepository extends JpaRepository<MissionDeviceAssignment, String> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"mission", "mission.order", "device"})
    List<MissionDeviceAssignment> findByIsCurrentTrueAndReleasedAtIsNullAndReplacementRequestedAtIsNotNullOrderByReplacementRequestedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT COUNT(DISTINCT mda.mission.id) FROM MissionDeviceAssignment mda
            WHERE mda.deviceRole = com.ondemandmonitoring.mission.enums.DeviceRole.MAIN
              AND mda.isCurrent = true
              AND mda.status = :status
            """)
    long countCurrentMainMissionsByStatus(MissionDeviceAssignmentStatus status);

    @Query("""
            SELECT assignment FROM MissionDeviceAssignment assignment
            JOIN FETCH assignment.replacementAssignment replacement
            JOIN FETCH replacement.device
            JOIN FETCH assignment.mission mission
            JOIN FETCH mission.order
            WHERE assignment.releasedAt IS NOT NULL
              AND EXISTS (
                  SELECT crew.id FROM MissionStaffAssignment crew
                  WHERE crew.mission = mission AND crew.staff.id = :staffId
                    AND crew.isCurrent = true AND crew.releasedAt IS NULL
                    AND crew.assignedAt <= assignment.releasedAt
              )
            ORDER BY assignment.releasedAt DESC, assignment.id DESC
            """)
    List<MissionDeviceAssignment> findRecentReplacementsForStaff(
            @org.springframework.data.repository.query.Param("staffId") String staffId,
            org.springframework.data.domain.Pageable pageable);

    boolean existsByMissionId(String missionId);

    Optional<MissionDeviceAssignment> findFirstByMissionIdOrderByCreatedAtDesc(String missionId);

    List<MissionDeviceAssignment> findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(String missionId);

    List<MissionDeviceAssignment> findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
            String missionId,
            DeviceRole deviceRole);

    @Query("""
            SELECT mda FROM MissionDeviceAssignment mda
            WHERE mda.mission.id = :missionId
              AND mda.device.id = :deviceId
              AND mda.isCurrent = true
            """)
    Optional<MissionDeviceAssignment> findByMissionIdAndDeviceIdAndIsCurrentTrue(String missionId, String deviceId);

    List<MissionDeviceAssignment> findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(
            String missionId,
            String deviceId);

    List<MissionDeviceAssignment> findByMissionId(String missionId);

    @Query("""
            SELECT mda FROM MissionDeviceAssignment mda
            WHERE mda.device.deviceCode = :deviceCode
              AND mda.isCurrent = true
              AND mda.mission.status IN :statuses
            """)
    List<MissionDeviceAssignment> findCurrentByDeviceCodeAndMissionStatusIn(
            String deviceCode,
            List<MissionStatus> statuses);

    @Query("""
            SELECT mda FROM MissionDeviceAssignment mda
            WHERE mda.device.id = :deviceId
              AND mda.isCurrent = true
              AND mda.mission.status IN :statuses
            """)
    List<MissionDeviceAssignment> findCurrentByDeviceIdAndMissionStatusIn(
            String deviceId,
            List<MissionStatus> statuses);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "device")
    List<MissionDeviceAssignment> findAllByMissionIdInAndIsCurrentTrue(List<String> missionIds);
}
