package com.ondemandmonitoring.finance.service;

public interface IFinalPaymentNotificationService {
    /** Starts the final-payment deadline and notifies the customer when the balance becomes payable. */
    void notifyIfEligible(String orderId);
}
