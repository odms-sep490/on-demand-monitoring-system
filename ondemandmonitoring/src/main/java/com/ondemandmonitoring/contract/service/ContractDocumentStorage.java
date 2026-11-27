package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.ContractDocument;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator.ValidatedPdf;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.cleanup.ObjectCleanupOutbox;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
@RequiredArgsConstructor
public class ContractDocumentStorage {
  private final IMediaObjectStorage storage;
  private final ObjectCleanupOutbox cleanupOutbox;

  public ContractDocument store(String orderId, ValidatedPdf file) {
    // The caller holds the order lock until its document reference commits.
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Contract upload requires a writable business transaction");
    String bucket = storage.bucket();
    String key = "order-contracts/orders/" + orderId + "/" + UUID.randomUUID() + ".pdf";
    String reservation =
        cleanupOutbox.reserveUpload(bucket, key, ContractObjectCleanupGuard.TYPE, orderId);
    cleanupOutbox.protectReservation(reservation);
    try {
      storage.put(
          bucket, key, "application/pdf", file.bytes().length,
          new ByteArrayInputStream(file.bytes()), "order-contract");
    } catch (RuntimeException e) {
      // The durable reservation also covers an upload that succeeded before a network failure.
      throw new ApiException(ErrorCode.CONTRACT_STORAGE_UNAVAILABLE);
    }
    // This update joins the caller's transaction, including the subsequent document save.
    // A rollback restores PENDING even when saving the reference fails after this method returns.
    cleanupOutbox.cancelReservation(reservation);
    ContractDocument document = new ContractDocument();
    document.setBucket(bucket);
    document.setKey(key);
    document.setByteSize(file.bytes().length);
    document.setFileName(file.fileName());
    document.setSha256(file.sha256());
    return document;
  }

  public void requireStored(ContractDocument document) {
    try {
      var info = storage.inspect(document.getBucket(), document.getKey());
      if (info == null
          || info.contentLength() == null
          || info.contentLength() != document.getByteSize()
          || !"application/pdf".equals(info.contentType())) throw new IllegalStateException();
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.CONTRACT_STORAGE_UNAVAILABLE);
    }
  }

  public String download(ContractDocument document) {
    return storage.createPresignedDownloadUrl(
        document.getBucket(), document.getKey(), "contract.pdf");
  }

  public long expiresInSeconds() {
    return storage.presignedUrlExpiresSeconds();
  }
}
