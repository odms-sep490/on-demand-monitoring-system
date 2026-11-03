package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceResponse(String id, String invoiceNumber, String orderId, String quoteId,
                              BigDecimal totalAmount, BigDecimal depositAmount,
                              BigDecimal paidAmount, BigDecimal remainingAmount,
                              InvoiceStatus status, DepositStatus depositStatus, Instant issuedAt,
                              boolean finalPaymentAllowed, Instant depositExpiresAt,
                              Instant finalPaymentExpiresAt,
                              Instant finalPaymentStartedAt,
                              String orderCancellationReason) {}
