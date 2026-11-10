package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionRequestItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultRevisionRequestItemRepository extends JpaRepository<ResultRevisionRequestItem, String> {
    List<ResultRevisionRequestItem> findByRequestIdOrderByDisplayOrderAsc(String requestId);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"request", "request.round", "roundItem", "roundItem.orderChecklistItem"})
    List<ResultRevisionRequestItem> findByRequestIdInOrderByCreatedAtAscIdAsc(List<String> requestIds);
    List<ResultRevisionRequestItem> findByCaseItemIdOrderByCreatedAtAsc(String caseItemId);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"request", "request.round", "caseItem"})
    List<ResultRevisionRequestItem> findByCaseItemIdInOrderByCreatedAtAscIdAsc(List<String> caseItemIds);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"request", "request.round"})
    List<ResultRevisionRequestItem> findByIdIn(List<String> ids);
}
