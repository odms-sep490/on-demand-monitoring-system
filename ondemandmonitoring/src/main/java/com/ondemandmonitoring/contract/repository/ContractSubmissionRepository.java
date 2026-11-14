package com.ondemandmonitoring.contract.repository;

import com.ondemandmonitoring.contract.domain.ContractSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractSubmissionRepository extends JpaRepository<ContractSubmission, String> {
  List<ContractSubmission> findAllByContractIdOrderBySubmissionNumberDesc(String contractId);
}
