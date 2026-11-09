package com.ondemandmonitoring.finance.gateway;

import java.math.BigDecimal;

public record PaymentLinkCommand(String transactionReference, BigDecimal amount,
                                 String description, String clientIp) {}
