package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionChecklistExecutionRepository extends JpaRepository<MissionChecklistExecution, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from MissionChecklistExecution e where e.id = :id")
    Optional<MissionChecklistExecution> findByIdForUpdate(@Param("id") String id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update mission_checklist_executions
               set execution_status = :executionStatus,
                   assessment_status = :assessmentStatus,
                   observation = :observation,
                   unable_to_verify_reason = :unableToVerifyReason,
                   started_at = :startedAt,
                   completed_at = :completedAt,
                   last_modified_by = :lastModifiedBy,
                   updated_at = :updatedAt,
                   version = version + 1
             where id = :id and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersionMatches(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionStatus") String executionStatus,
            @Param("assessmentStatus") String assessmentStatus,
            @Param("observation") String observation,
            @Param("unableToVerifyReason") String unableToVerifyReason,
            @Param("startedAt") Instant startedAt,
            @Param("completedAt") Instant completedAt,
            @Param("lastModifiedBy") String lastModifiedBy,
            @Param("updatedAt") Instant updatedAt);

    @Query("select e from MissionChecklistExecution e join fetch e.orderChecklistItem join fetch e.missionChecklistAssignment a where e.missionId = :missionId order by a.displayOrder, a.id")
    List<MissionChecklistExecution> findOrderedByMissionId(@Param("missionId") String missionId);

    boolean existsByOrderId(String orderId);
}
