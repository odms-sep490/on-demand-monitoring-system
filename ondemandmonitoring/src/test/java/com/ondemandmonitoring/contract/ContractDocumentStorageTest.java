package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.contract.service.ContractDocumentStorage;
import com.ondemandmonitoring.contract.service.ContractObjectCleanupGuard;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator;
import com.ondemandmonitoring.media.cleanup.ObjectCleanupOutbox;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ContractDocumentStorageTest {
  IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
  ObjectCleanupOutbox outbox = mock(ObjectCleanupOutbox.class);
  ContractDocumentStorage service = new ContractDocumentStorage(storage, outbox);

  @BeforeEach
  void setup() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    when(storage.bucket()).thenReturn("private");
    when(outbox.reserveUpload(eq("private"), anyString(), eq(ContractObjectCleanupGuard.TYPE), eq("order")))
        .thenReturn("reservation");
  }

  @AfterEach
  void cleanup() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void reservesAndProtectsBeforeUploadAndCancelsWithinTheBusinessTransaction() {
    var pdf = ContractPdfValidator.validate(ContractTestFiles.pdf());
    var file = service.store("order", pdf);
    assertThat(file.getKey()).startsWith("order-contracts/orders/order/").endsWith(".pdf");
    assertThat(file.getBucket()).isEqualTo("private");
    assertThat(file.getSha256()).isEqualTo(pdf.sha256());
    var order = inOrder(storage, outbox);
    order.verify(storage).bucket();
    order.verify(outbox).reserveUpload("private", file.getKey(), ContractObjectCleanupGuard.TYPE, "order");
    order.verify(outbox).protectReservation("reservation");
    order.verify(storage).put(eq("private"), eq(file.getKey()), eq("application/pdf"),
        eq((long) pdf.bytes().length), any(), eq("order-contract"));
    order.verify(outbox).cancelReservation("reservation");
    verify(storage, never()).deleteQuietly(any(), any());
  }

  @Test
  void uploadFailureLeavesTheDurableReservationForRecovery() {
    when(storage.put(anyString(), anyString(), anyString(), anyLong(), any(), anyString()))
        .thenThrow(new IllegalStateException("Network failure after S3 received the object"));
    assertThatThrownBy(() -> service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf())))
        .isInstanceOf(ApiException.class);
    verify(outbox).protectReservation("reservation");
    verify(outbox, never()).cancelReservation(any());
    verify(storage, never()).deleteQuietly(any(), any());
  }

  @Test
  void reservationFailurePreventsUploadingAnUntrackedObject() {
    when(outbox.reserveUpload(any(), any(), any(), any())).thenThrow(new IllegalStateException("Database offline"));
    assertThatThrownBy(() -> service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf())))
        .isInstanceOf(IllegalStateException.class);
    verify(storage, never()).put(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
  }

  @Test
  void expiredReservationCannotUploadAfterAWorkerHasAlreadyClaimedIt() {
    doThrow(new IllegalStateException("Reservation is no longer pending")).when(outbox).protectReservation("reservation");
    assertThatThrownBy(() -> service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf())))
        .isInstanceOf(IllegalStateException.class);
    verify(storage, never()).put(anyString(), anyString(), anyString(), anyLong(), any(), anyString());
  }

  @Test
  void missingOrReadOnlyTransactionIsRejectedBeforeAnyStorageMutation() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertThatThrownBy(() -> service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf())))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> service.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf())))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(storage, outbox);
  }
}
