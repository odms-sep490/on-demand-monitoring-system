package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.DeviceRole;

import java.util.List;
import java.util.Optional;

@Repository
public interface MissionDeviceAssignmentRepository extends JpaRepository<MissionDeviceAssignment, String> {

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
