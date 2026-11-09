package com.ondemandmonitoring.pricing.repository;

import com.ondemandmonitoring.pricing.domain.CustomWorkAssessment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomWorkAssessmentRepository extends JpaRepository<CustomWorkAssessment, String> {
    Optional<CustomWorkAssessment> findByOrderId(String orderId);
}
