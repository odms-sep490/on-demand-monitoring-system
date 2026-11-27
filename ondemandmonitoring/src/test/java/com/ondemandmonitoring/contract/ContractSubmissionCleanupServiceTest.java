package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.ContractSubmissionCleanupService;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.media.cleanup.ObjectCleanupOutbox;
import com.ondemandmonitoring.contract.service.ContractObjectCleanupGuard;
import com.ondemandmonitoring.order.domain.Order;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;

class ContractSubmissionCleanupServiceTest {
  ContractSubmissionRepository submissions = mock(ContractSubmissionRepository.class);
  OrderContractRepository contracts = mock(OrderContractRepository.class);
  ObjectCleanupOutbox outbox = mock(ObjectCleanupOutbox.class);
  PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  Instant now = Instant.parse("2026-11-14T04:00:00Z");
  ContractSubmission row;

  ContractSubmissionCleanupService service(Instant instant) {
    return new ContractSubmissionCleanupService(
        submissions, contracts, outbox, transactions, Clock.fixed(instant, ZoneOffset.UTC));
  }

  @BeforeEach
  void setup() {
    when(outbox.enqueue(any(), any(), any(), any())).thenReturn("task");
    when(outbox.status("task")).thenReturn("PENDING");
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    Order order = new Order();
    order.setId("order");
    Quote quote = new Quote();
    quote.setOrder(order);
    OrderContract contract = new OrderContract();
    contract.setId("contract");
    contract.setQuote(quote);
    row = new ContractSubmission();
    row.setId("old");
    row.setContract(contract);
    row.setSubmissionNumber(1);
    row.setStatus(ContractSubmissionStatus.SUPERSEDED);
    row.setSupersededAt(now.minusSeconds(120));
    row.setCleanupNextAttemptAt(now.minusSeconds(60));
    var document = OrderContractServiceTest.document();
    document.setKey("order-contracts/orders/order/old.pdf");
    row.setDocument(document);
    when(submissions.findCleanupCandidates(any(), any(), any())).thenReturn(List.of("old"));
    when(submissions.findByIdForUpdate("old")).thenReturn(Optional.of(row));
    when(submissions.existsByContractIdAndSubmissionNumberGreaterThan("contract", 1))
        .thenReturn(true);
  }

  @Test
  void enqueuesFirstAndMarksDeletedOnlyAfterOutboxCompletion() {
    assertThat(service(now).cleanup()).isZero();
    verify(outbox).enqueue("private-test", "order-contracts/orders/order/old.pdf",
        ContractObjectCleanupGuard.Superseded.TYPE, "old");
    assertThat(row.getDocumentDeletedAt()).isNull();
    when(outbox.status("task")).thenReturn("COMPLETED");
    assertThat(service(now).cleanup()).isEqualTo(1);
    assertThat(row.getDocumentDeletedAt()).isEqualTo(now);
    assertThat(service(now).cleanup()).isZero();
    verify(outbox, times(2)).enqueue(any(), any(), any(), any());
  }

  @Test
  void deadOutboxTaskRetainsDomainWorkAndBacksOffUntilAnOperatorRetries() {
    when(outbox.status("task")).thenReturn("DEAD");
    assertThat(service(now).cleanup()).isZero();
    assertThat(row.getDocumentDeletedAt()).isNull();
    assertThat(row.getCleanupAttempts()).isEqualTo(1);
    assertThat(row.getCleanupNextAttemptAt()).isEqualTo(now.plusSeconds(60));
    assertThat(service(now.plusSeconds(20)).cleanup()).isZero();
    when(outbox.status("task")).thenReturn("COMPLETED");
    assertThat(service(now.plusSeconds(61)).cleanup()).isEqualTo(1);
  }

  @Test
  void protectedOrInFlightWorkIsNeverMistakenForADeletedObject() {
    for (String state : List.of("PENDING", "PROCESSING", "CANCELLED")) {
      when(outbox.status("task")).thenReturn(state);
      assertThat(service(now).cleanup()).isZero();
      assertThat(row.getDocumentDeletedAt()).isNull();
    }
  }

  @Test
  void failedDispatchLeavesTheDomainQueueRetryable() {
    when(outbox.enqueue(any(), any(), any(), any())).thenThrow(new IllegalStateException("Unavailable"));
    assertThat(service(now).cleanup()).isZero();
    assertThat(row.getDocumentDeletedAt()).isNull();
    assertThat(row.getCleanupAttempts()).isEqualTo(1);
  }

  @Test
  void confirmedOrPendingDocumentCannotBeDeleted() {
    for (var status :
        List.of(
            ContractSubmissionStatus.PENDING,
            ContractSubmissionStatus.CONFIRMED,
            ContractSubmissionStatus.CHANGES_REQUESTED)) {
      row.setStatus(status);
      assertThat(service(now).cleanup()).isZero();
    }
    verifyNoInteractions(outbox);
  }

  @Test
  void requiresADurablyCommittedReplacement() {
    when(submissions.existsByContractIdAndSubmissionNumberGreaterThan("contract", 1))
        .thenReturn(false);
    assertThat(service(now).cleanup()).isZero();
    verifyNoInteractions(outbox);
  }

  @Test
  void protectsOfficialDocumentAndOtherFileReferences() {
    when(contracts.existsByDocumentBucketAndDocumentKey(any(), any())).thenReturn(true);
    assertThat(service(now).cleanup()).isZero();
    verifyNoInteractions(outbox);
    when(contracts.existsByDocumentBucketAndDocumentKey(any(), any())).thenReturn(false);
    when(submissions.existsByDocumentBucketAndDocumentKeyAndIdNot(any(), any(), any()))
        .thenReturn(true);
    row.setCleanupNextAttemptAt(now);
    assertThat(service(now).cleanup()).isZero();
    verifyNoInteractions(outbox);
  }

  @Test
  void gracePeriodAndStoragePrefixAreEnforced() {
    row.setSupersededAt(now);
    assertThat(service(now).cleanup()).isZero();
    verifyNoInteractions(outbox);
    row.setSupersededAt(now.minusSeconds(120));
    row.getDocument().setKey("drone-media/important.pdf");
    assertThat(service(now).cleanup()).isZero();
    verifyNoInteractions(outbox);
  }
}
