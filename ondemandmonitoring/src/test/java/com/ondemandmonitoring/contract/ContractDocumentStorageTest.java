package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.contract.service.ContractDocumentStorage;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;

class ContractDocumentStorageTest {
  IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
  ContractDocumentStorage service = new ContractDocumentStorage(storage);

  @AfterEach
  void cleanup() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void rollbackRemovesOnlyTheNewlyUploadedPrivateObject() {
    TransactionSynchronizationManager.initSynchronization();
    when(storage.put(anyString(), eq("application/pdf"), anyLong(), any(), eq("order-contract")))
        .thenAnswer(
            call -> new IMediaObjectStorage.StoredObject("private", call.getArgument(0), null));
    var file = service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf()));
    assertThat(file.getKey()).startsWith("order-contracts/orders/order/").endsWith(".pdf");
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verify(storage).deleteQuietly("private", file.getKey());
  }

  @Test
  void successfulCommitRetainsTheHistoricalFile() {
    TransactionSynchronizationManager.initSynchronization();
    when(storage.put(anyString(), anyString(), anyLong(), any(), anyString()))
        .thenReturn(new IMediaObjectStorage.StoredObject("private", "key", null));
    service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf()));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    verify(storage, never()).deleteQuietly(anyString(), anyString());
  }
}
