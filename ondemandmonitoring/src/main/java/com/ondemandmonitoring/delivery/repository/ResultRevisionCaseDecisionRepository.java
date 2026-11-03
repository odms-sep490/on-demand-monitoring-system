package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResultRevisionCaseDecisionRepository extends JpaRepository<ResultRevisionCaseDecision, String> {
    List<ResultRevisionCaseDecision> findByCaseItemIdOrderByCreatedAtAsc(String caseItemId);
}
