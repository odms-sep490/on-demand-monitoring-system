package com.ondemandmonitoring.refund.service;

public interface IRefundHoldService {
    boolean isOrderOnRefundHold(String orderId);

    void requireOrderNotOnRefundHold(String orderId);
}
