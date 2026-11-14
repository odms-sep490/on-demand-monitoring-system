package com.ondemandmonitoring.contract.repository;

import com.ondemandmonitoring.contract.domain.ContractCancellation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractCancellationRepository
    extends JpaRepository<ContractCancellation, String> {
  Optional<ContractCancellation> findByOrderId(String orderId);
}
