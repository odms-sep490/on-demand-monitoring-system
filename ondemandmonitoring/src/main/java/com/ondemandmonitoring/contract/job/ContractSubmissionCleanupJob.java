package com.ondemandmonitoring.contract.job;

import com.ondemandmonitoring.contract.service.ContractSubmissionCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.contract.cleanup",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ContractSubmissionCleanupJob {
  private final ContractSubmissionCleanupService cleanup;

  @Scheduled(fixedDelayString = "${app.contract.cleanup.fixed-delay-ms:60000}")
  public void run() {
    cleanup.cleanup();
  }
}
