package com.ondemandmonitoring.refund.dto;

public record RefundReceiptAccessResponse(
        String url,
        String contentType,
        String fileName,
        long expiresInSeconds) {
}
