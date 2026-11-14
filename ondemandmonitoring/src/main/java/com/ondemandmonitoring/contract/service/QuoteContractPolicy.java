package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.OrderContract;
import com.ondemandmonitoring.contract.enums.ContractStatus;
import com.ondemandmonitoring.contract.repository.OrderContractRepository;
import com.ondemandmonitoring.finance.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Invoked under the Order -> Quote lock shared by quote preparation and acceptance. */
@Service
@RequiredArgsConstructor
public class QuoteContractPolicy {
  private final OrderContractRepository contracts;
  private final ContractDocumentStorage storage;

  public boolean hasDocument(String quoteId) {
    return contracts.existsByQuoteId(quoteId);
  }

  public OrderContract requirePrepared(Quote quote) {
    OrderContract contract =
        contracts
            .findByQuoteId(quote.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND));
    if (contract.getQuoteVersion() != quote.getQuoteVersion()
        || contract.getStatus() != ContractStatus.AWAITING_SIGNATURE
        || contract.getDocument() == null)
      throw new ApiException(ErrorCode.CONTRACT_STATE_CONFLICT);
    storage.requireStored(contract.getDocument());
    return contract;
  }

  public void attachInvoice(Invoice invoice) {
    OrderContract contract = requirePrepared(invoice.getQuote());
    if (contract.getInvoice() != null && !contract.getInvoice().getId().equals(invoice.getId()))
      throw new ApiException(ErrorCode.CONTRACT_STATE_CONFLICT);
    contract.setInvoice(invoice);
    contracts.saveAndFlush(contract);
  }
}
