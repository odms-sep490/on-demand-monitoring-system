package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultReviewRoundEvidenceRepository extends JpaRepository<ResultReviewRoundEvidence, String> {
    boolean existsByMediaAsset_Id(String mediaId);
    boolean existsByMediaAsset_Mission_Id(String missionId);

    List<ResultReviewRoundEvidence> findByRoundItemIdOrderByCreatedAtAsc(String roundItemId);
    @EntityGraph(attributePaths = {"mediaAsset.captureMetadata", "mediaAsset.derivative", "mediaAsset.deviceAssignment", "roundItem"})
    List<ResultReviewRoundEvidence> findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(Iterable<String> roundItemIds);

    /** Customer libraries expose only the current published snapshot, never superseded rounds. */
    @Query("""
            select distinct evidence.mediaAsset.id
              from ResultReviewRoundEvidence evidence, OrderDelivery delivery
             where delivery.order.id in :orderIds
               and evidence.roundItem.round.id = delivery.currentReviewRoundId
               and evidence.roundItem.round.order.id = delivery.order.id
            """)
    Set<String> findCurrentPublishedMediaAssetIdsByOrderIds(@Param("orderIds") List<String> orderIds);
}
