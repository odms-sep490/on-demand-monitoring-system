package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultReviewRound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultReviewRoundRepository extends JpaRepository<ResultReviewRound, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ResultReviewRound r where r.id = :id")
    Optional<ResultReviewRound> findByIdForUpdate(@Param("id") String id);
    List<ResultReviewRound> findByOrderIdOrderByRoundNumberDesc(String orderId);
    Optional<ResultReviewRound> findByOrderIdAndRoundNumber(String orderId, int roundNumber);
    org.springframework.data.domain.Page<ResultReviewRound> findByOrderIdOrderByRoundNumberDesc(
            String orderId, org.springframework.data.domain.Pageable pageable);

}
