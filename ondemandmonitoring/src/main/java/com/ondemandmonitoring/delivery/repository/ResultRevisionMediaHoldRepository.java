package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionMediaHold;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultRevisionMediaHoldRepository extends JpaRepository<ResultRevisionMediaHold, String> {
    Optional<ResultRevisionMediaHold> findByResultRevisionCaseIdAndMediaAssetId(String caseId, String mediaAssetId);

    // Protect all media of an active case, including reserve images and new rework captures.
    @Query("select count(m) > 0 from MediaAsset m where m.id = :mediaId and ("
            + "exists (select c.id from ResultRevisionCase c where c.order.id = m.mission.order.id and c.active = true) "
            + "or exists (select h.id from ResultRevisionMediaHold h where h.mediaAsset.id = m.id and h.expiresAt > :now))")
    boolean hasActiveHold(@Param("mediaId") String mediaId, @Param("now") Instant now);

    @Query("select count(c) > 0 from ResultRevisionCase c, MediaAsset m "
            + "where c.id = :caseId and c.active = true and m.id = :mediaId and c.order.id = m.mission.order.id")
    boolean belongsToActiveCase(@Param("mediaId") String mediaId, @Param("caseId") String caseId);

    @Modifying(flushAutomatically = true)
    @Query("update ResultRevisionMediaHold h set h.expiresAt = :expiresAt, h.version = h.version + 1 "
            + "where h.resultRevisionCase.id = :caseId")
    int releaseCase(@Param("caseId") String caseId, @Param("expiresAt") Instant expiresAt);
}
