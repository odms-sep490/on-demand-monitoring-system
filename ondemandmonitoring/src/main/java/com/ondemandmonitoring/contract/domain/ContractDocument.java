package com.ondemandmonitoring.contract.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Embeddable
@Getter
@Setter
public class ContractDocument {
  @Column(name = "storage_bucket", nullable = false)
  private String bucket;

  @Column(name = "storage_key", nullable = false, length = 1000)
  private String key;

  @Column(name = "file_name", nullable = false, length = 180)
  private String fileName;

  @Column(name = "byte_size", nullable = false)
  private long byteSize;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;
}
