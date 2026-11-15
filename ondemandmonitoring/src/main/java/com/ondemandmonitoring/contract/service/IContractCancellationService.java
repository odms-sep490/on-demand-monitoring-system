package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.contract.dto.*;

public interface IContractCancellationService {
  ContractCancellationResponse get(String orderId);

  ContractCancellationResponse request(String orderId, ContractCancellationRequest request);

  ContractCancellationResponse decide(String orderId, ContractCancellationDecision request);
}
