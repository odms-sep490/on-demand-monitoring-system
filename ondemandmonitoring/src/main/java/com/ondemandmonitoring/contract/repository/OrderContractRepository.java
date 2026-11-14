package com.ondemandmonitoring.contract.repository;

import com.ondemandmonitoring.contract.domain.OrderContract;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderContractRepository extends JpaRepository<OrderContract, String> {
  Optional<OrderContract> findByInvoiceId(String invoiceId);

  Optional<OrderContract> findByQuoteId(String quoteId);

  boolean existsByQuoteId(String quoteId);

  boolean existsByContractNumber(String contractNumber);

  boolean existsByDocumentBucketAndDocumentKey(String bucket, String key);
}
