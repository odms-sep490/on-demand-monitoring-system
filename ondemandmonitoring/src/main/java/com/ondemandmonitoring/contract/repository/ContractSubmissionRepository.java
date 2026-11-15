package com.ondemandmonitoring.contract.repository;

import com.ondemandmonitoring.contract.domain.ContractSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractSubmissionRepository extends JpaRepository<ContractSubmission, String> {
  @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"contract", "contract.invoice", "contract.invoice.order"})
  List<ContractSubmission> findByContract_Invoice_Order_Customer_IdAndReviewedAtIsNotNullOrderByReviewedAtDesc(
      String customerId, org.springframework.data.domain.Pageable pageable);

  @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"contract", "contract.invoice", "contract.invoice.order"})
  List<ContractSubmission> findAllByOrderBySubmittedAtDesc(org.springframework.data.domain.Pageable pageable);

  List<ContractSubmission> findAllByContractIdOrderBySubmissionNumberDesc(String contractId);
}
