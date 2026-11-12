package com.ondemandmonitoring.refund.repository;

import com.ondemandmonitoring.refund.enums.TransferOutcome;
import com.ondemandmonitoring.refund.domain.RefundTransferAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundTransferAttemptRepository extends JpaRepository<RefundTransferAttempt, String> {
    @EntityGraph(attributePaths = {"recordedBy", "receipts"})
    List<RefundTransferAttempt> findAllByRefundIdOrderByAttemptNumberAsc(String refundId);

    Optional<RefundTransferAttempt> findByTransactionReference(String transactionReference);

    Optional<RefundTransferAttempt> findByIdAndRefundId(String id, String refundId);

    boolean existsByRefundIdAndOutcome(
            String refundId, TransferOutcome outcome);

    int countByRefundId(String refundId);
}
