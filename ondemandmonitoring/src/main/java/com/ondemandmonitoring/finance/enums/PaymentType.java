package com.ondemandmonitoring.finance.enums;

public enum PaymentType {
    DEPOSIT,
    /** Historical DB value only; current APIs create Mission milestone payments instead. */
    @Deprecated FINAL_PAYMENT,
    MISSION_PAYMENT,
    REFUND
}
