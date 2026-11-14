package com.ondemandmonitoring.contract.dto;

import com.ondemandmonitoring.contract.enums.*;
import java.time.Instant;
import java.util.List;

public record ContractResponse(
    boolean required,
    String id,
    long version,
    String invoiceId,
    String quoteId,
    int quoteVersion,
    String contractNumber,
    ContractStatus status,
    Instant issuedAt,
    String issuedBy,
    Instant confirmedAt,
    String confirmedBy,
    Document document,
    List<Submission> submissions) {
  public record Document(String fileName, long byteSize, String sha256) {}

  public record Submission(
      String id,
      int submissionNumber,
      ContractSubmissionStatus status,
      Instant submittedAt,
      String submittedBy,
      Instant reviewedAt,
      String reviewedBy,
      String reviewNote,
      Document document) {}
}
