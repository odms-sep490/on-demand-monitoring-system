package com.ondemandmonitoring.finance.gateway;

import java.math.BigDecimal;

public record VerifiedWebhook(String transactionReference, BigDecimal amount, String currency,
                              String providerTransactionId, String transactionDateTime,
                              String responseCode, String transactionStatus, boolean successful) {}
