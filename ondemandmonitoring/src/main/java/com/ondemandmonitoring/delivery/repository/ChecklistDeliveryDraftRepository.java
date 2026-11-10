package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ChecklistDeliveryDraft;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistDeliveryDraftRepository extends JpaRepository<ChecklistDeliveryDraft, String> {
    @Query("select d from ChecklistDeliveryDraft d where d.order.id = :orderId and d.missionId is null")
    Optional<ChecklistDeliveryDraft> findByOrderId(@Param("orderId") String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ChecklistDeliveryDraft d where d.order.id = :orderId and d.missionId is null")
    Optional<ChecklistDeliveryDraft> findByOrderIdForUpdate(@Param("orderId") String orderId);

    void deleteByOrderId(String orderId);

    @Query("select d from ChecklistDeliveryDraft d where d.order.id=:orderId and d.missionId=:missionId")
    Optional<ChecklistDeliveryDraft> findScoped(@Param("orderId") String orderId, @Param("missionId") String missionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ChecklistDeliveryDraft d where d.order.id=:orderId and d.missionId=:missionId")
    Optional<ChecklistDeliveryDraft> findScopedForUpdate(@Param("orderId") String orderId, @Param("missionId") String missionId);
}
