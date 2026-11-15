package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.contract.domain.ContractSubmission;
import com.ondemandmonitoring.contract.enums.ContractSubmissionStatus;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable cleanup queue lives on the superseded submission, committed with its replacement. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractSubmissionCleanupService {
  private final ContractSubmissionRepository submissions;
  private final OrderContractRepository contracts;
  private final IMediaObjectStorage storage;
  private final PlatformTransactionManager transactions;
  private final Clock clock;

  @Value("${app.contract.cleanup.grace-seconds:60}")
  private long graceSeconds = 60;

  public int cleanup() {
    Instant now = Instant.now(clock), cutoff = now.minusSeconds(Math.max(0, graceSeconds));
    var ids = submissions.findCleanupCandidates(now, cutoff, PageRequest.of(0, 50));
    int deleted = 0;
    for (String id : ids) {
      try {
        Boolean result =
            new TransactionTemplate(transactions).execute(tx -> deleteOne(id, now, cutoff));
        if (Boolean.TRUE.equals(result)) deleted++;
      } catch (RuntimeException failure) {
        // A DB failure leaves the durable queue unchanged. S3 DeleteObject is idempotent on retry.
        log.warn(
            "Contract submission cleanup transaction failed; will retry. submissionId={},"
                + " failure={}",
            id,
            failure.getClass().getSimpleName());
      }
    }
    return deleted;
  }

  private boolean deleteOne(String id, Instant now, Instant cutoff) {
    // Serialize workers across application instances. No Order/Invoice lock is held during S3 I/O.
    ContractSubmission row = submissions.findByIdForUpdate(id).orElse(null);
    if (row == null
        || row.getStatus() != ContractSubmissionStatus.SUPERSEDED
        || row.getDocumentDeletedAt() != null
        || row.getSupersededAt() == null
        || row.getSupersededAt().isAfter(cutoff)
        || row.getCleanupNextAttemptAt() == null
        || row.getCleanupNextAttemptAt().isAfter(now)) return false;
    try {
      var document = row.getDocument();
      String prefix =
          "order-contracts/orders/" + row.getContract().getQuote().getOrder().getId() + "/";
      if (document == null
          || document.getBucket() == null
          || document.getKey() == null
          || !document.getKey().startsWith(prefix)
          || !document.getKey().endsWith(".pdf")
          || !submissions.existsByContractIdAndSubmissionNumberGreaterThan(
              row.getContract().getId(), row.getSubmissionNumber())
          || contracts.existsByDocumentBucketAndDocumentKey(document.getBucket(), document.getKey())
          || submissions.existsByDocumentBucketAndDocumentKeyAndIdNot(
              document.getBucket(), document.getKey(), id))
        throw new IllegalStateException("Document remains protected");
      // Use throwing delete: deleteQuietly would lose failed cleanup work.
      storage.delete(document.getBucket(), document.getKey());
      row.setDocumentDeletedAt(now);
      return true;
    } catch (RuntimeException failure) {
      int attempts = Math.min(row.getCleanupAttempts() + 1, 1000);
      row.setCleanupAttempts(attempts);
      row.setCleanupNextAttemptAt(
          now.plusSeconds(Math.min(3600L, 60L * (1L << Math.min(attempts - 1, 6)))));
      log.warn(
          "Contract submission cleanup failed; will retry. submissionId={}, failure={}",
          id,
          failure.getClass().getSimpleName());
      return false;
    }
  }
}
