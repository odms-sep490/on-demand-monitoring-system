package com.ondemandmonitoring.device.repository;

import com.ondemandmonitoring.device.domain.Device;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

@Repository
public interface DeviceRepository extends JpaRepository<Device, String> {

    @EntityGraph(attributePaths = {"deviceModel", "deviceModel.deviceTypes"})
    @Query("""
            select d from Device d
            where (d.status = com.ondemandmonitoring.device.enums.DeviceStatus.AVAILABLE
              or (d.status = com.ondemandmonitoring.device.enums.DeviceStatus.RESERVED
                and exists (select a.id from MissionDeviceAssignment a
                  where a.device = d and a.mission.id = :missionId and a.isCurrent = true)))
              and not exists (select l.id from ResourceTimeLock l
                where l.resourceId = d.id and l.releasedAt is null and l.mission.id <> :missionId
                  and l.startTime < :end and l.endTime > :start)
            order by d.serialNumber, d.id
            """)
    List<Device> findAvailableForMission(
            @Param("missionId") String missionId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    boolean existsBySerialNumber(String serialNumber);

    boolean existsBySerialNumberAndIdNot(String serialNumber, String id);

    Optional<Device> findBySerialNumber(String serialNumber);

    Optional<Device> findByDeviceCode(String deviceCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findByIdForUpdate(@Param("id") String id);
}
