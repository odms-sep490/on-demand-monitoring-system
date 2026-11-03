package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ChecklistDeliveryDraft;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistDeliveryDraftRepository extends JpaRepository<ChecklistDeliveryDraft, String> {
    Optional<ChecklistDeliveryDraft> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ChecklistDeliveryDraft d where d.order.id = :orderId")
    Optional<ChecklistDeliveryDraft> findByOrderIdForUpdate(@Param("orderId") String orderId);

    void deleteByOrderId(String orderId);
}
