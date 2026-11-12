package com.ondemandmonitoring.refund.repository;

import com.ondemandmonitoring.refund.domain.Refund;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, String> {
    @EntityGraph(attributePaths = {"order", "customer", "invoice", "requestedBy", "processingAdmin", "completedBy",
            "rejectedBy", "cancelledBy"})
    Optional<Refund> findByOrderId(String orderId);

    @EntityGraph(attributePaths = {"order", "customer", "invoice", "requestedBy", "processingAdmin", "completedBy",
            "rejectedBy", "cancelledBy"})
    List<Refund> findAllByStatusOrderByRequestedAtAsc(RefundStatus status);

    @EntityGraph(attributePaths = {"order", "customer", "invoice", "requestedBy", "processingAdmin", "completedBy",
            "rejectedBy", "cancelledBy"})
    List<Refund> findAllByOrderByRequestedAtAsc();

    boolean existsByOrderId(String orderId);

    boolean existsByOrderIdAndStatusIn(String orderId, java.util.Collection<RefundStatus> statuses);

    @Query("select coalesce(sum(r.refundedAmount), 0) from Refund r where r.status = :status")
    BigDecimal sumRefundedAmountByStatus(@Param("status") RefundStatus status);

    @Query("select coalesce(sum(r.refundableAmount), 0) from Refund r where r.status in :statuses")
    BigDecimal sumRefundableAmountByStatuses(@Param("statuses") java.util.Collection<RefundStatus> statuses);

    long countByStatusIn(java.util.Collection<RefundStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.order.id = :orderId")
    Optional<Refund> findByOrderIdForUpdate(@Param("orderId") String orderId);
}
