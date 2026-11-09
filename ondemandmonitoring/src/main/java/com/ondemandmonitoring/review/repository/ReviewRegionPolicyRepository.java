package com.ondemandmonitoring.review.repository;

import com.ondemandmonitoring.review.domain.ReviewRegionPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRegionPolicyRepository extends JpaRepository<ReviewRegionPolicy, String> {

    List<ReviewRegionPolicy> findAllByActiveTrueOrderByPriorityDescCodeAsc();

    Optional<ReviewRegionPolicy> findByCode(String code);
}
