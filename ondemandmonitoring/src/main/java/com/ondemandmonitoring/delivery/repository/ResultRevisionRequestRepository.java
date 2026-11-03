package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultRevisionRequestRepository extends JpaRepository<ResultRevisionRequest, String> {
    Optional<ResultRevisionRequest> findByRoundId(String roundId);
    Optional<ResultRevisionRequest> findFirstByTicketIdOrderBySubmittedAtDesc(String ticketId);
    Optional<ResultRevisionRequest> findByCustomerIdAndClientRequestId(String customerId, UUID clientRequestId);
    List<ResultRevisionRequest> findByOrderIdOrderBySubmittedAtDesc(String orderId);
}
