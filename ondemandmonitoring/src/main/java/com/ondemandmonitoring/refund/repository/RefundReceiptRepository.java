package com.ondemandmonitoring.refund.repository;

import com.ondemandmonitoring.refund.domain.RefundReceipt;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundReceiptRepository extends JpaRepository<RefundReceipt, String> {
    @EntityGraph(attributePaths = {"attempt", "attempt.refund", "attempt.refund.order", "attempt.refund.customer"})
    Optional<RefundReceipt> findById(String id);

    long countByAttemptId(String attemptId);
}
