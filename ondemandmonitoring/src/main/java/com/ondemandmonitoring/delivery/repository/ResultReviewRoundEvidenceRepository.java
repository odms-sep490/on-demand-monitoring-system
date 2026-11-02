package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface ResultReviewRoundEvidenceRepository extends JpaRepository<ResultReviewRoundEvidence, String> {
    List<ResultReviewRoundEvidence> findByRoundItemIdOrderByCreatedAtAsc(String roundItemId);
    @EntityGraph(attributePaths = {"mediaAsset", "roundItem"})
    List<ResultReviewRoundEvidence> findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(Iterable<String> roundItemIds);
}
