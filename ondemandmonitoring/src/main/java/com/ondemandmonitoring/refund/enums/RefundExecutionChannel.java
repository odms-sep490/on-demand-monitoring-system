package com.ondemandmonitoring.refund.enums;

/** Where the Admin executed the refund; OMSS only records the external result. */
public enum RefundExecutionChannel {
    BANK_TRANSFER,
    VNPAY_MERCHANT_PORTAL
}
