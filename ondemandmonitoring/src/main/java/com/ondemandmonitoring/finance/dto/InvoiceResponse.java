package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceResponse(String id, String invoiceNumber, String orderId, String quoteId,
                              BigDecimal totalAmount, BigDecimal depositAmount,
                              BigDecimal paidAmount, BigDecimal remainingAmount,
                              InvoiceStatus status, Instant issuedAt,
                              boolean finalPaymentAllowed, Instant depositExpiresAt,
                              String orderCancellationReason) {}
