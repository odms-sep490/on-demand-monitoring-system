package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionRequestItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultRevisionRequestItemRepository extends JpaRepository<ResultRevisionRequestItem, String> {
    List<ResultRevisionRequestItem> findByRequestIdOrderByDisplayOrderAsc(String requestId);
    List<ResultRevisionRequestItem> findByCaseItemIdOrderByCreatedAtAsc(String caseItemId);
}
