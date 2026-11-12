package com.ondemandmonitoring.refund.dto;

import java.time.Instant;

public record RefundReceiptResponse(
        String id,
        String fileName,
        String contentType,
        long byteSize,
        Instant uploadedAt,
        String accessPath) {
}
