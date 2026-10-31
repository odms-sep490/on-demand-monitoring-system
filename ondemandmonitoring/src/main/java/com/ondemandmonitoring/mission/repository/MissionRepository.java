package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, String>, JpaSpecificationExecutor<Mission> {
    @Override
    Optional<Mission> findById(String id);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Mission m WHERE m.id = :id")
    Optional<Mission> findByIdForUpdate(@Param("id") String id);

    @EntityGraph(attributePaths = "order")
    @Query("SELECT m FROM Mission m WHERE m.id = :id")
    Optional<Mission> findByIdWithOrder(@Param("id") String id);

    Optional<Mission> findByMissionCode(String missionCode);

    /** Order aggregate query; never use an Order ID as exact Mission identity. */
    List<Mission> findAllByOrderIdOrderByScheduledStartAtAscIdAsc(String orderId);

    @Query("select m.id from Mission m where m.order.id = :orderId order by m.id")
    List<String> findIdsByOrderIdOrderById(@Param("orderId") String orderId);

    @Query("select m.order.id from Mission m where m.id = :missionId")
    Optional<String> findOrderIdByMissionId(@Param("missionId") String missionId);

    @Query(value = """
            select exists(select 1 from mission_device_assignments where mission_id=:missionId and released_at is null)
                or exists(select 1 from mission_staff_assignments where mission_id=:missionId and released_at is null)
                or exists(select 1 from resource_time_locks where mission_id=:missionId and released_at is null)
                or exists(select 1 from device_connections where mission_id=:missionId
                    and (connection_status='CONNECTED' or telemetry_active=true))
            """, nativeQuery = true)
    boolean hasUnsafeCancellationResources(@Param("missionId") String missionId);


    boolean existsByMissionCode(String missionCode);

    boolean existsByOrder_IdAndStatusNot(String orderId, MissionStatus status);

    List<Mission> findByOrder_Customer_Id(String customerId);

    @EntityGraph(attributePaths = "order")
    Page<Mission> findByOrder_Customer_IdAndStatusIn(
            String customerId, List<MissionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    Optional<Mission> findByIdAndOrder_Customer_Id(String id, String customerId);

    @Query("""
            SELECT DISTINCT m FROM Mission m
            JOIN MissionStaffAssignment msa ON msa.mission = m
            WHERE msa.staff.id = :staffId
              AND m.status IN :statuses
            """)
    List<Mission> findByStaffIdAndStatusIn(@Param("staffId") String staffId, @Param("statuses") List<MissionStatus> statuses);

    List<Mission> findByStatusIn(List<MissionStatus> statuses);

    List<Mission> findByFlightPermitStatusAndFlightPermitResourceHoldUntilLessThanEqualAndFlightPermitResourcesReleasedAtIsNull(
            com.ondemandmonitoring.mission.enums.FlightPermitStatus status,
            java.time.Instant holdUntil);

    long countByStatusIn(java.util.Collection<MissionStatus> statuses);

    long countByStatus(MissionStatus status);

    long countByStatusAndCompletedAtGreaterThanEqual(MissionStatus status, java.time.Instant completedAt);

    @EntityGraph(attributePaths = "order")
    Page<Mission> findByStatusInOrderByScheduledStartAtAsc(
            java.util.Collection<MissionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    Page<Mission> findByStatusInOrderByUpdatedAtDesc(
            java.util.Collection<MissionStatus> statuses, Pageable pageable);

    @Query("""
            select count(m) from Mission m
            where m.status in :statuses
              and (not exists (select a.id from MissionStaffAssignment a where a.mission = m and a.isCurrent = true)
                or not exists (select d.id from MissionDeviceAssignment d where d.mission = m and d.isCurrent = true))
            """)
    long countMissingResources(@Param("statuses") java.util.Collection<MissionStatus> statuses);

    @EntityGraph(attributePaths = "order")
    @Query("""
            select m from Mission m
            where m.status in :statuses
              and (not exists (select a.id from MissionStaffAssignment a where a.mission = m and a.isCurrent = true)
                or not exists (select d.id from MissionDeviceAssignment d where d.mission = m and d.isCurrent = true))
            order by m.scheduledStartAt asc
            """)
    List<Mission> findMissingResources(
            @Param("statuses") java.util.Collection<MissionStatus> statuses, Pageable pageable);

}
