package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionMediaHold;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultRevisionMediaHoldRepository extends JpaRepository<ResultRevisionMediaHold, String> {
    Optional<ResultRevisionMediaHold> findByResultRevisionCaseIdAndMediaAssetId(String caseId, String mediaAssetId);

    @Query("select count(h) > 0 from ResultRevisionMediaHold h "
            + "where h.mediaAsset.id = :mediaId and h.expiresAt > :now and h.resultRevisionCase.active = true")
    boolean hasActiveHold(@Param("mediaId") String mediaId, @Param("now") Instant now);
}
