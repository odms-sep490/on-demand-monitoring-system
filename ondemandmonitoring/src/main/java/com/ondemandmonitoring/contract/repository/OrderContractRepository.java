package com.ondemandmonitoring.contract.repository;

import com.ondemandmonitoring.contract.domain.OrderContract;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderContractRepository extends JpaRepository<OrderContract, String> {
  Optional<OrderContract> findByInvoiceId(String invoiceId);

  @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"invoice", "invoice.order"})
  java.util.List<OrderContract> findByInvoice_Order_Customer_IdOrderByIssuedAtDesc(
      String customerId, org.springframework.data.domain.Pageable pageable);

  boolean existsByContractNumber(String contractNumber);
}
