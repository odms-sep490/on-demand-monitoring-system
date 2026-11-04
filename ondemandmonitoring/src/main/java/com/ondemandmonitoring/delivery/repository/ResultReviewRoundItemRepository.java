package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultReviewRoundItemRepository extends JpaRepository<ResultReviewRoundItem, String> {
    List<ResultReviewRoundItem> findByRoundIdOrderByDisplayOrderAsc(String roundId);
    org.springframework.data.domain.Page<ResultReviewRoundItem> findByRoundIdOrderByDisplayOrderAscIdAsc(
            String roundId, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"round", "orderChecklistItem"})
    List<ResultReviewRoundItem> findByRoundIdInOrderByRoundIdAscDisplayOrderAscIdAsc(List<String> roundIds);

}
