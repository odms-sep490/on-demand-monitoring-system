package com.ondemandmonitoring.device.repository;

import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceTelemetryRepository extends JpaRepository<DeviceTelemetry, String> {

    Optional<DeviceTelemetry> findTopByMissionDeviceAssignmentIdOrderByRecordedAtDesc(String assignmentId);

    @Query("""
            select t from DeviceTelemetry t
            where t.missionDeviceAssignment.mission.id = :missionId
              and t.missionDeviceAssignment.device.id = :deviceId
            order by t.recordedAt desc
            """)
    List<DeviceTelemetry> findLatestForMissionDevice(String missionId, String deviceId, Pageable pageable);
}

