package com.ondemandmonitoring.finance.enums;

public enum PaymentWebhookResult {
    PROCESSED,
    ALREADY_CONFIRMED,
    ORDER_NOT_FOUND,
    INVALID_AMOUNT,
    IGNORED,
    LATE_PAYMENT_RECORDED
}
