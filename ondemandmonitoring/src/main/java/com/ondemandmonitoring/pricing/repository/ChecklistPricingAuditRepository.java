package com.ondemandmonitoring.pricing.repository;

import com.ondemandmonitoring.pricing.domain.ChecklistPricingAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;

public interface ChecklistPricingAuditRepository extends JpaRepository<ChecklistPricingAudit, String> {
    @EntityGraph(attributePaths = {"orderChecklistItem", "orderChecklistItem.order", "reviewedBy"})
    List<ChecklistPricingAudit> findAllByOrderByReviewedAtDesc(Pageable pageable);
}
