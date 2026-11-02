package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionRequestItemMedia;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface ResultRevisionRequestItemMediaRepository extends JpaRepository<ResultRevisionRequestItemMedia, String> {
    List<ResultRevisionRequestItemMedia> findByRequestItemId(String requestItemId);
    @EntityGraph(attributePaths = {"roundEvidence", "roundEvidence.mediaAsset", "roundEvidence.roundItem"})
    List<ResultRevisionRequestItemMedia> findByRequestItemIdIn(Iterable<String> requestItemIds);
}
