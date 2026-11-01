package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.DeviceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceConnectionRepository extends JpaRepository<DeviceConnection, String> {

    List<DeviceConnection> findByMissionId(String missionId);

    Optional<DeviceConnection> findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(String missionId, String connectionStatus);

    @Query("""
            SELECT dc FROM DeviceConnection dc
            JOIN dc.deviceAssignment assignment
            JOIN assignment.device device
            WHERE (device.id = :deviceIdentifier OR device.deviceCode = :deviceIdentifier)
              AND dc.connectionStatus = :connectionStatus
              AND dc.telemetryActive = true
            ORDER BY dc.connectedAt DESC
            """)
    List<DeviceConnection> findActiveTelemetryByDeviceIdentifier(
            String deviceIdentifier,
            String connectionStatus);
}
