package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultReviewRoundItemRepository extends JpaRepository<ResultReviewRoundItem, String> {
    List<ResultReviewRoundItem> findByRoundIdOrderByDisplayOrderAsc(String roundId);
}
