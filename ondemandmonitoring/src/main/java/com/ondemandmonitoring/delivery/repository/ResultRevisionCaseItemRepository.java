package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface ResultRevisionCaseItemRepository extends JpaRepository<ResultRevisionCaseItem, String> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"orderChecklistItem"})
    List<ResultRevisionCaseItem> findByResultRevisionCaseIdOrderByCreatedAtAsc(String caseId);
    List<ResultRevisionCaseItem> findByResultRevisionCaseIdAndIdInOrderByCreatedAtAsc(String caseId, Collection<String> ids);
    List<ResultRevisionCaseItem> findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(
            String caseId, Collection<String> checklistItemIds);
    Optional<ResultRevisionCaseItem> findByResultRevisionCaseIdAndOrderChecklistItemId(String caseId, String checklistItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ResultRevisionCaseItem i where i.id = :id")
    Optional<ResultRevisionCaseItem> findByIdForUpdate(@Param("id") String id);
}
