package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.ContractDocument;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator.ValidatedPdf;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
@RequiredArgsConstructor
public class ContractDocumentStorage {
  private final IMediaObjectStorage storage;

  public ContractDocument store(String orderId, ValidatedPdf file) {
    IMediaObjectStorage.StoredObject object;
    try {
      object =
          storage.put(
              "order-contracts/orders/" + orderId + "/" + UUID.randomUUID() + ".pdf",
              "application/pdf",
              file.bytes().length,
              new ByteArrayInputStream(file.bytes()),
              "order-contract");
    } catch (RuntimeException e) {
      throw new ApiException(ErrorCode.CONTRACT_STORAGE_UNAVAILABLE);
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) storage.deleteQuietly(object.bucket(), object.key());
            }
          });
    }
    ContractDocument document = new ContractDocument();
    document.setBucket(object.bucket());
    document.setKey(object.key());
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
