package com.ondemandmonitoring.contract.mapper;

import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.dto.ContractResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderContractMapper {
  ContractResponse.Document document(ContractDocument document);

  ContractResponse.Submission submission(ContractSubmission submission);
}
