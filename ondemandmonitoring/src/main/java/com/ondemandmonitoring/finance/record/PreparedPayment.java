package com.ondemandmonitoring.finance.record;

import com.ondemandmonitoring.finance.dto.PaymentResponse;
import java.math.BigDecimal;

public record PreparedPayment(String id, String transactionReference, BigDecimal amount,
                              String description, boolean existing, PaymentResponse response) {}
