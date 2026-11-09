package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.MissionChecklistAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionChecklistAssignmentRepository extends JpaRepository<MissionChecklistAssignment, String> {
    List<MissionChecklistAssignment> findByMissionId(String missionId);
    @Query("select a from MissionChecklistAssignment a join fetch a.orderChecklistItem where a.missionId = :missionId and a.removedAt is null order by a.displayOrder, a.id")
    List<MissionChecklistAssignment> findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(@Param("missionId") String missionId);

    @Query("select a from MissionChecklistAssignment a join fetch a.orderChecklistItem where a.orderId = :orderId and a.removedAt is null order by a.missionId, a.displayOrder, a.id")
    List<MissionChecklistAssignment> findActiveByOrderId(@Param("orderId") String orderId);

    Optional<MissionChecklistAssignment> findByMissionIdAndOrderChecklistItemIdAndRemovedAtIsNull(
            String missionId, String orderChecklistItemId);

    boolean existsByOrderIdAndRemovedAtIsNull(String orderId);
}
