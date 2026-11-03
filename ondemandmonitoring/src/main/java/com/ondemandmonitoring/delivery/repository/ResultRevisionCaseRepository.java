package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ResultRevisionCaseRepository extends JpaRepository<ResultRevisionCase, String> {
    Optional<ResultRevisionCase> findByOrderIdAndActiveTrue(String orderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ResultRevisionCase c where c.order.id = :orderId and c.active = true")
    Optional<ResultRevisionCase> findByOrderIdAndActiveTrueForUpdate(@Param("orderId") String orderId);
    Optional<ResultRevisionCase> findByTicketId(String ticketId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ResultRevisionCase c where c.id = :id")
    Optional<ResultRevisionCase> findByIdForUpdate(@Param("id") String id);
}
