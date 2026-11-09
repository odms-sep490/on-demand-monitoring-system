package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.PaymentProvider;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(String id, String invoiceId, Long paymentCode, PaymentType type,
                              BigDecimal amount, String currency, PaymentStatus status,
                              PaymentProvider provider, String paymentUrl,
                              String transactionReference, String providerTransactionId,
                              Instant paidAt) {}
