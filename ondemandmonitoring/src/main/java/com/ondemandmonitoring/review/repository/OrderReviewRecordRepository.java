package com.ondemandmonitoring.review.repository;

import com.ondemandmonitoring.review.domain.OrderReviewRecord;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderReviewRecordRepository extends JpaRepository<OrderReviewRecord, String> {
    List<OrderReviewRecord> findByOrderId(String orderId);

    Optional<OrderReviewRecord> findByOrderIdAndCode(String orderId, ReviewCriterionCode code);

    boolean existsByOrderIdAndCode(String orderId, ReviewCriterionCode code);

    long deleteByCode(ReviewCriterionCode code);
}
