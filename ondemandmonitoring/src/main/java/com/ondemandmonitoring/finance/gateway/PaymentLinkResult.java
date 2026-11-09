package com.ondemandmonitoring.finance.gateway;

public record PaymentLinkResult(String providerTransactionId, String paymentUrl, String providerRequestDate) {}
