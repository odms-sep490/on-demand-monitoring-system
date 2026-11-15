package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.contract.dto.*;
import org.springframework.web.multipart.MultipartFile;

public interface IOrderContractService {
  ContractResponse get(String orderId);

  ContractResponse getForQuote(String orderId, String quoteId);

  ContractResponse issue(String orderId, String quoteId, String contractNumber, MultipartFile file);

  ContractResponse prepare(String orderId, String quoteId, long expectedPricingRevision, String contractNumber, MultipartFile file);

  ContractResponse submit(String orderId, long expectedVersion, MultipartFile file);

  ContractResponse confirm(String orderId, ContractReviewRequest request);

  ContractResponse requestChanges(String orderId, ContractReviewRequest request);

  ContractDownloadResponse download(String orderId, String submissionId);

  ContractDownloadResponse downloadForQuote(String orderId, String quoteId, String submissionId);
}
